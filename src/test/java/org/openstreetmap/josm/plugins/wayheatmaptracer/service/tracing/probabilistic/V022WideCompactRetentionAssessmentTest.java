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
        return new WideCompactRetentionAssessment.Input(scene,strength,checks(),new WideCompactRetentionAssessment.Distances(m(distance),m(distance),m(distance),m(distance)),true,"faint-apex".equals(scene),
                new WideCompactRetentionAssessment.Amplitudes(m(bendCandidate),m(bendReference),"x=40..60m","x=40..60m","y=0m","y=0m"),
                new WideCompactRetentionAssessment.AbsoluteAmplitude(m("faint-apex".equals(scene) ? bendCandidate : Math.max(2.7,bendCandidate)),m("faint-apex".equals(scene) ? bendReference : Math.max(2.7,bendReference)),m(3),m(2.7),"x=40..60m","y=0m"),
                geometry(0,refMax),geometry(0,candidateMax));
    }
    @Test void suitableStrongAndWeakReferencesUseTheirSeparateRawAndFinalLimits() {
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,WideCompactRetentionAssessment.assess(input("strong",WideCompactRetentionAssessment.Strength.STRONG,.25*WideCompactRetentionAssessment.P,1,1,1,1)).verdict());
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,WideCompactRetentionAssessment.assess(input("weak",WideCompactRetentionAssessment.Strength.WEAK,.5*WideCompactRetentionAssessment.P,1,1,1,1)).verdict());
        var weakGeometry=geometry(1.153846,1.518346);
        var weak= input("diffuse-three-strand",WideCompactRetentionAssessment.Strength.WEAK,0,1,1,1,1);
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(weak.sceneId(),weak.strength(),weak.runnerChecks(),weak.distances(),true,weak.absoluteAmplitudeApplicable(),weak.amplitudes(),weak.absoluteAmplitude(),weakGeometry,weakGeometry)).verdict());
        var strong=geometry(.5*WideCompactRetentionAssessment.P,WideCompactRetentionAssessment.P);
        var strongInput=input("strong",WideCompactRetentionAssessment.Strength.STRONG,0,1,1,1,1);
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.SUITABLE,WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(strongInput.sceneId(),strongInput.strength(),strongInput.runnerChecks(),strongInput.distances(),true,strongInput.absoluteAmplitudeApplicable(),strongInput.amplitudes(),strongInput.absoluteAmplitude(),strong,strong)).referenceDisposition());
    }
    @Test void weakAnalyticOracleUsesPAndStrongOracleUsesHalfP() {
        var weak=input("weak",WideCompactRetentionAssessment.Strength.WEAK,0,1,1,1,1);
        var weakBad=geometry(1.001*WideCompactRetentionAssessment.P,1);
        var wr=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(weak.sceneId(),weak.strength(),weak.runnerChecks(),weak.distances(),true,weak.absoluteAmplitudeApplicable(),weak.amplitudes(),weak.absoluteAmplitude(),weakBad,geometry(0,1)));
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.SUITABILITY_FAILED,wr.referenceDisposition());
        var strong=input("strong",WideCompactRetentionAssessment.Strength.STRONG,0,1,1,1,1);
        var strongBad=geometry(.5001*WideCompactRetentionAssessment.P,1);
        var sr=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(strong.sceneId(),strong.strength(),strong.runnerChecks(),strong.distances(),true,strong.absoluteAmplitudeApplicable(),strong.amplitudes(),strong.absoluteAmplitude(),strongBad,geometry(0,1)));
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.SUITABILITY_FAILED,sr.referenceDisposition());
    }
    @Test void missingAntiZigZagEvidenceIsUnavailable() {
        var base=input("scene",WideCompactRetentionAssessment.Strength.STRONG,0,1,1,1,1);
        var absent=new WideCompactRetentionAssessment.GeometryOracle(m(0),m(1),null);
        var r=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,base.absoluteAmplitudeApplicable(),base.amplitudes(),base.absoluteAmplitude(),absent,base.candidateGeometry()));
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.UNAVAILABLE,r.referenceDisposition());
        var candidateAbsent=new WideCompactRetentionAssessment.GeometryOracle(m(0),m(1),null);
        var candidateResult=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,base.absoluteAmplitudeApplicable(),base.amplitudes(),base.absoluteAmplitude(),base.referenceGeometry(),candidateAbsent));
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,candidateResult.verdict());
        var diffuse=input("diffuse-three-strand",WideCompactRetentionAssessment.Strength.WEAK,2.307692,1,1,1,1);
        var absentDiffuseCandidate=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(diffuse.sceneId(),diffuse.strength(),diffuse.runnerChecks(),diffuse.distances(),true,false,diffuse.amplitudes(),null,geometry(1.926272,1.926272),candidateAbsent));
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,absentDiffuseCandidate.verdict());
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.UNAVAILABLE,absentDiffuseCandidate.referenceDisposition());
    }
    @Test void displacementOutsideEitherStrengthLimitFails() {
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,WideCompactRetentionAssessment.assess(input("strong",WideCompactRetentionAssessment.Strength.STRONG,.251*WideCompactRetentionAssessment.P,1,1,1,1)).distanceStatus());
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,WideCompactRetentionAssessment.assess(input("weak",WideCompactRetentionAssessment.Strength.WEAK,.501*WideCompactRetentionAssessment.P,1,1,1,1)).distanceStatus());
    }
    @Test void eachRawAndFinalDistanceMetricIsIndependentlyGatedAndMissingIsUnavailable() {
        double p=WideCompactRetentionAssessment.P;
        for(int i=0;i<4;i++) {
            var vals=new WideCompactRetentionAssessment.Metric[]{m(.25*p),m(.5*p),m(.25*p),m(.5*p)};
            vals[i]=m((i%2==0?.25:.5)*p+1e-6);
            var base=input("strong",WideCompactRetentionAssessment.Strength.STRONG,0,1,1,1,1);
            var failed=WideCompactRetentionAssessment.assess(copyWithDistances(base,new WideCompactRetentionAssessment.Distances(vals[0],vals[1],vals[2],vals[3])));
            assertEquals(WideCompactRetentionAssessment.Status.FAILED,failed.distanceStatus(),"metric index "+i);
            vals[i]=new WideCompactRetentionAssessment.Metric(null,E);
            var missing=WideCompactRetentionAssessment.assess(copyWithDistances(base,new WideCompactRetentionAssessment.Distances(vals[0],vals[1],vals[2],vals[3])));
            assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,missing.distanceStatus(),"metric index "+i);
        }
    }
    private static WideCompactRetentionAssessment.Input copyWithDistances(WideCompactRetentionAssessment.Input in,WideCompactRetentionAssessment.Distances d) {
        return new WideCompactRetentionAssessment.Input(in.sceneId(),in.strength(),in.runnerChecks(),d,in.supportedBend(),in.absoluteAmplitudeApplicable(),in.amplitudes(),in.absoluteAmplitude(),in.referenceGeometry(),in.candidateGeometry());
    }
    @Test void unchangedFaintAmplitudeKeepsRetentionAndAbsoluteDebtSeparate() {
        var r=WideCompactRetentionAssessment.assess(input("faint-apex",WideCompactRetentionAssessment.Strength.WEAK,0,1.923077,1.923077,1,1));
        assertEquals(WideCompactRetentionAssessment.Status.PASS,r.amplitudeRetentionStatus()); assertEquals(1.0,r.amplitudeRetentionRatio());
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,r.absoluteQualityDebt()); assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS_WITH_BASELINE_DEBT,r.verdict());
    }
    @Test void frozenFaintSceneRequiresAbsoluteCandidateAndReferenceEvidence() {
        var base=input("faint-apex",WideCompactRetentionAssessment.Strength.WEAK,0,1.923077,1.923077,1,1);
        var missing=new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,base.absoluteAmplitudeApplicable(),
                base.amplitudes(),null,base.referenceGeometry(),base.candidateGeometry());
        var r=WideCompactRetentionAssessment.assess(missing);
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,r.absoluteQualityDebt());
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,r.verdict());
        var missingReference=new WideCompactRetentionAssessment.AbsoluteAmplitude(m(1.923077),null,m(3),m(2.7),"x=40..60m","y=0m");
        var missingRefResult=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,true,
                base.amplitudes(),missingReference,base.referenceGeometry(),base.candidateGeometry()));
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,missingRefResult.referenceAbsoluteQuality());
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,missingRefResult.verdict());
    }
    @Test void absoluteCandidateAndReferenceDebtAreReportedIndependently() {
        var base=input("faint-apex",WideCompactRetentionAssessment.Strength.WEAK,0,1.923077,1.923077,1,1);
        var candidateOnly=new WideCompactRetentionAssessment.AbsoluteAmplitude(m(2.6),m(2.8),m(3),m(2.7),"x=40..60m","y=0m");
        var c=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,true,
                base.amplitudes(),candidateOnly,base.referenceGeometry(),base.candidateGeometry()));
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,c.candidateAbsoluteQuality());
        assertEquals(WideCompactRetentionAssessment.Status.PASS,c.referenceAbsoluteQuality());
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS_WITH_CANDIDATE_DEBT,c.verdict());
        var both=new WideCompactRetentionAssessment.AbsoluteAmplitude(m(2.6),m(2.5),m(3),m(2.7),"x=40..60m","y=0m");
        var b=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,true,
                base.amplitudes(),both,base.referenceGeometry(),base.candidateGeometry()));
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS_WITH_BASELINE_DEBT,b.verdict());
        var wrongInterval=new WideCompactRetentionAssessment.AbsoluteAmplitude(m(2.6),m(2.5),m(3),m(2.7),"x=0..20m","y=0m");
        var unavailable=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,true,
                base.amplitudes(),wrongInterval,base.referenceGeometry(),base.candidateGeometry()));
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,unavailable.absoluteQualityDebt());
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,unavailable.verdict());
    }
    @Test void retentionBelowNinetyPercentFails() {
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,WideCompactRetentionAssessment.assess(input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,.899,1,1,1)).amplitudeRetentionStatus());
        var base=input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,1,1,1,1);
        var mismatched=new WideCompactRetentionAssessment.Amplitudes(m(1),m(1),"x=40..60m","x=41..60m","y=0m","y=0m");
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,false,mismatched,null,base.referenceGeometry(),base.candidateGeometry())).amplitudeRetentionStatus());
    }
    @Test void missingZeroReferenceOrNonfiniteAmplitudeCannotMakeAUsableRatio() {
        var base=input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,1,1,1,1);
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,base.absoluteAmplitudeApplicable(),
                new WideCompactRetentionAssessment.Amplitudes(null,m(1),"i","i","b","b"),base.absoluteAmplitude(),base.referenceGeometry(),base.candidateGeometry())).amplitudeRetentionStatus());
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,WideCompactRetentionAssessment.assess(input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,1,0,1,1)).amplitudeRetentionStatus());
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,WideCompactRetentionAssessment.assess(input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,Double.NaN,1,1,1)).amplitudeRetentionStatus());
    }
    @Test void diffuseUnsuitableReferenceRetainsFailedDistanceAndCannotExcuseCandidateFailure() {
        var seed=input("diffuse-three-strand",WideCompactRetentionAssessment.Strength.WEAK,2.307692,1,1,1.926272,1.518346);
        var r=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(seed.sceneId(),seed.strength(),seed.runnerChecks(),seed.distances(),true,seed.absoluteAmplitudeApplicable(),seed.amplitudes(),seed.absoluteAmplitude(),geometry(1.926272,1.926272),geometry(1.153846,1.518346)));
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,r.distanceStatus());
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.UNSUITABLE_REFERENCE,r.referenceDisposition());
        assertEquals(WideCompactRetentionAssessment.Verdict.ASSESSED_WITH_UNSUITABLE_REFERENCE,r.verdict());
        var unsafe=new WideCompactRetentionAssessment.Input(seed.sceneId(),seed.strength(),seed.runnerChecks(),seed.distances(),true,seed.absoluteAmplitudeApplicable(),seed.amplitudes(),seed.absoluteAmplitude(),geometry(1.926272,1.926272),geometry(1.153846,1.518346));
        var badChecks=new WideCompactRetentionAssessment.RunnerChecks(E,E,E,E,E,E,E,E, WideCompactRetentionAssessment.Evidence.FAILED);
        var blocked=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(unsafe.sceneId(),unsafe.strength(),badChecks,unsafe.distances(),true,unsafe.absoluteAmplitudeApplicable(),unsafe.amplitudes(),unsafe.absoluteAmplitude(),unsafe.referenceGeometry(),unsafe.candidateGeometry()));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,blocked.verdict()); assertFalse(blocked.releaseQualified());
        var badGeometry=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(unsafe.sceneId(),unsafe.strength(),unsafe.runnerChecks(),unsafe.distances(),true,unsafe.absoluteAmplitudeApplicable(),unsafe.amplitudes(),unsafe.absoluteAmplitude(),unsafe.referenceGeometry(),geometry(0,1.01*WideCompactRetentionAssessment.P)));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,badGeometry.verdict());
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.SUITABILITY_FAILED,badGeometry.referenceDisposition());
        var lowRetention=new WideCompactRetentionAssessment.Amplitudes(m(.89),m(1),"x=40..60m","x=40..60m","y=0m","y=0m");
        var retentionFailed=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(seed.sceneId(),seed.strength(),seed.runnerChecks(),seed.distances(),true,seed.absoluteAmplitudeApplicable(),lowRetention,seed.absoluteAmplitude(),geometry(1.926272,1.926272),geometry(1.153846,1.518346)));
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,retentionFailed.amplitudeRetentionStatus());
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,retentionFailed.verdict());
        var missingRetention=new WideCompactRetentionAssessment.Amplitudes(null,m(1),"x=40..60m","x=40..60m","y=0m","y=0m");
        var retentionUnavailable=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(seed.sceneId(),seed.strength(),seed.runnerChecks(),seed.distances(),true,seed.absoluteAmplitudeApplicable(),missingRetention,seed.absoluteAmplitude(),geometry(1.926272,1.926272),geometry(1.153846,1.518346)));
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,retentionUnavailable.amplitudeRetentionStatus());
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,retentionUnavailable.verdict());
    }
    @Test void unsuitableReferenceExceptionIsFrozenToDiffuseSceneAndAssessmentNeverQualifiesRelease() {
        var r=WideCompactRetentionAssessment.assess(input("another-scene",WideCompactRetentionAssessment.Strength.WEAK,2.307692,1,1,1.926272,1.518346));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,r.verdict()); assertFalse(r.releaseQualified());
    }
}
