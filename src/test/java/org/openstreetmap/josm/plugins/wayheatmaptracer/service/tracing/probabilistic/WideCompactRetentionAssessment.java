package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;

/** Pure, versioned contract assessment. It does not validate runner evidence or qualify a release. */
final class WideCompactRetentionAssessment {
    static final String VERSION = "wide-compact-performance-retention-v1";

    enum Evidence { RUNNER_VALIDATED, FAILED, UNAVAILABLE, NOT_APPLICABLE }
    enum Strength { STRONG, WEAK }
    enum Status { PASS, FAILED, UNAVAILABLE, NOT_APPLICABLE }
    enum Verdict { RETENTION_PASS, RETENTION_PASS_WITH_BASELINE_DEBT, FAILED, UNAVAILABLE, ASSESSED_WITH_UNSUITABLE_REFERENCE }
    enum ReferenceDisposition { SUITABLE, UNSUITABLE_REFERENCE, SUITABILITY_FAILED, UNAVAILABLE }

    record RunnerChecks(Evidence referenceExecution, Evidence referenceIdentity, Evidence referenceNativeAuthority,
            Evidence referenceDenseComplete, Evidence candidateExecution, Evidence candidateIdentity,
            Evidence candidateNativeAuthority, Evidence candidateBranch, Evidence candidateSafety) { }
    record Metric(Double value, Evidence evidence) { }
    record Distances(Metric rawP95, Metric rawCertifiedMaximum, Metric finalP95, Metric finalCertifiedMaximum) { }
    record Amplitudes(Metric candidate, Metric reference, String candidateInterval, String referenceInterval,
            String candidateBaseline, String referenceBaseline) { }
    record AbsoluteAmplitude(Metric candidate, Metric target, Metric requiredMinimum) { }
    record GeometryOracle(Metric p95, Metric certifiedMaximum, Evidence antiZigZag) { }
    record Input(String sceneId, Strength strength, RunnerChecks runnerChecks, Distances distances,
            boolean supportedBend, Amplitudes amplitudes, AbsoluteAmplitude absoluteAmplitude,
            GeometryOracle referenceGeometry, GeometryOracle candidateGeometry) { }
    record Result(String version, Verdict verdict, Status distanceStatus, ReferenceDisposition referenceDisposition,
            Status amplitudeRetentionStatus, Double amplitudeRetentionRatio, Status absoluteQualityDebt,
            boolean releaseQualified, List<String> reasons) { }

    static final double P = WideCompactScenes.P;

    static Result assess(Input input) {
        if (input == null || input.sceneId() == null || input.strength() == null) {
            return result(Verdict.UNAVAILABLE, Status.UNAVAILABLE, ReferenceDisposition.UNAVAILABLE,
                    Status.UNAVAILABLE, null, Status.UNAVAILABLE, "missing assessment identity or strength");
        }
        var reasons = new java.util.ArrayList<String>();
        Status required = requiredChecks(input.runnerChecks());
        Status candidateOracle = oracleStatus(input.candidateGeometry(), true);
        Status referenceOracle = oracleStatus(input.referenceGeometry(), false);
        Status distance = distanceStatus(input.distances(), input.strength());
        Status amplitude = input.supportedBend() ? amplitudeStatus(input.amplitudes()) : Status.NOT_APPLICABLE;
        Status debt = absoluteStatus(input.absoluteAmplitude());
        ReferenceDisposition disposition;
        if (referenceOracle == Status.PASS) disposition = ReferenceDisposition.SUITABLE;
        else if (referenceOracle == Status.UNAVAILABLE) disposition = ReferenceDisposition.UNAVAILABLE;
        else if ("diffuse-three-strand".equals(input.sceneId()) && candidateOracle == Status.PASS
                && required == Status.PASS) disposition = ReferenceDisposition.UNSUITABLE_REFERENCE;
        else disposition = ReferenceDisposition.SUITABILITY_FAILED;

        if (required == Status.FAILED || candidateOracle == Status.FAILED || distance == Status.FAILED
                || amplitude == Status.FAILED || (disposition == ReferenceDisposition.SUITABILITY_FAILED)) {
            reasons.add("one or more mandatory candidate, reference, fidelity, or retention checks failed");
            Verdict verdict = disposition == ReferenceDisposition.UNSUITABLE_REFERENCE
                    ? Verdict.ASSESSED_WITH_UNSUITABLE_REFERENCE : Verdict.FAILED;
            return new Result(VERSION, verdict, distance, disposition, amplitude,
                    amplitudeRatio(input.amplitudes()), debt, false, List.copyOf(reasons));
        }
        if (required == Status.UNAVAILABLE || candidateOracle == Status.UNAVAILABLE || distance == Status.UNAVAILABLE
                || amplitude == Status.UNAVAILABLE || disposition == ReferenceDisposition.UNAVAILABLE) {
            reasons.add("required runner evidence or measurement is unavailable");
            return new Result(VERSION, Verdict.UNAVAILABLE, distance, disposition, amplitude,
                    amplitudeRatio(input.amplitudes()), debt, false, List.copyOf(reasons));
        }
        if (disposition == ReferenceDisposition.UNSUITABLE_REFERENCE) {
            reasons.add("frozen diffuse reference fails independent geometry oracle; measured distance status retained");
            return new Result(VERSION, Verdict.ASSESSED_WITH_UNSUITABLE_REFERENCE, distance, disposition,
                    amplitude, amplitudeRatio(input.amplitudes()), debt, false, List.copyOf(reasons));
        }
        Verdict verdict = debt == Status.FAILED ? Verdict.RETENTION_PASS_WITH_BASELINE_DEBT : Verdict.RETENTION_PASS;
        if (debt == Status.FAILED) reasons.add("absolute amplitude target debt remains separately visible");
        return new Result(VERSION, verdict, distance, disposition, amplitude, amplitudeRatio(input.amplitudes()),
                debt, false, List.copyOf(reasons));
    }

    private static Result result(Verdict verdict, Status distance, ReferenceDisposition reference, Status amplitude,
            Double ratio, Status debt, String reason) {
        return new Result(VERSION, verdict, distance, reference, amplitude, ratio, debt, false, List.of(reason));
    }

    private static Status requiredChecks(RunnerChecks c) {
        if (c == null) return Status.UNAVAILABLE;
        Evidence[] values = {c.referenceExecution(), c.referenceIdentity(), c.referenceNativeAuthority(),
                c.referenceDenseComplete(), c.candidateExecution(), c.candidateIdentity(), c.candidateNativeAuthority(),
                c.candidateBranch(), c.candidateSafety()};
        boolean unavailable = false;
        for (Evidence value : values) {
            if (value == Evidence.FAILED) return Status.FAILED;
            if (value != Evidence.RUNNER_VALIDATED) unavailable = true;
        }
        return unavailable ? Status.UNAVAILABLE : Status.PASS;
    }

    private static Status distanceStatus(Distances d, Strength strength) {
        if (d == null || strength == null) return Status.UNAVAILABLE;
        double p95Limit = (strength == Strength.STRONG ? .25 : .5) * P;
        double maxLimit = (strength == Strength.STRONG ? .5 : 1.0) * P;
        Metric[] metrics = {d.rawP95(), d.rawCertifiedMaximum(), d.finalP95(), d.finalCertifiedMaximum()};
        for (int i = 0; i < metrics.length; i++) {
            Double value = valid(metrics[i]);
            if (value == null) return Status.UNAVAILABLE;
            double limit = (i % 2 == 0) ? p95Limit : maxLimit;
            if (value > limit) return Status.FAILED;
        }
        return Status.PASS;
    }

    private static Status oracleStatus(GeometryOracle oracle, boolean candidate) {
        if (oracle == null) return Status.UNAVAILABLE;
        Double p95 = valid(oracle.p95()), max = valid(oracle.certifiedMaximum());
        if (p95 == null || max == null || oracle.antiZigZag() == Evidence.UNAVAILABLE
                || oracle.antiZigZag() == Evidence.NOT_APPLICABLE) return Status.UNAVAILABLE;
        if (p95 > .5 * P || max > P || oracle.antiZigZag() == Evidence.FAILED) return Status.FAILED;
        return Status.PASS;
    }

    private static Status amplitudeStatus(Amplitudes a) {
        if (a == null || !same(a.candidateInterval(), a.referenceInterval())
                || !same(a.candidateBaseline(), a.referenceBaseline())) return Status.FAILED;
        Double candidate = valid(a.candidate()), reference = valid(a.reference());
        if (candidate == null || reference == null || reference <= 0) return Status.UNAVAILABLE;
        return candidate / reference >= .90 ? Status.PASS : Status.FAILED;
    }

    private static Double amplitudeRatio(Amplitudes a) {
        if (a == null || !same(a.candidateInterval(), a.referenceInterval())
                || !same(a.candidateBaseline(), a.referenceBaseline())) return null;
        Double candidate = valid(a.candidate()), reference = valid(a.reference());
        return candidate == null || reference == null || reference <= 0 ? null : candidate / reference;
    }

    private static Status absoluteStatus(AbsoluteAmplitude a) {
        if (a == null) return Status.NOT_APPLICABLE;
        Double candidate = valid(a.candidate()), target = valid(a.target()), minimum = valid(a.requiredMinimum());
        if (candidate == null || target == null || minimum == null) return Status.UNAVAILABLE;
        return candidate >= minimum ? Status.PASS : Status.FAILED;
    }

    private static Double valid(Metric metric) {
        if (metric == null || metric.evidence() != Evidence.RUNNER_VALIDATED || metric.value() == null
                || !Double.isFinite(metric.value()) || metric.value() < 0) return null;
        return metric.value();
    }

    private static boolean same(String a, String b) { return a != null && !a.isBlank() && a.equals(b); }

    private WideCompactRetentionAssessment() { }
}
