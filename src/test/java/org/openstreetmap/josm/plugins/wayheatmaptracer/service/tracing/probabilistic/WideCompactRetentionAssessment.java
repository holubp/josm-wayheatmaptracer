package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;

/** Pure, versioned contract assessment. It does not validate runner evidence or qualify a release. */
final class WideCompactRetentionAssessment {
    static final String VERSION = "wide-compact-performance-retention-v1";

    enum Evidence { RUNNER_VALIDATED, FAILED, UNAVAILABLE, NOT_APPLICABLE }
    enum Strength { STRONG, WEAK }
    enum Status { PASS, FAILED, UNAVAILABLE, NOT_APPLICABLE }
    enum Verdict { RETENTION_PASS, RETENTION_PASS_WITH_BASELINE_DEBT, RETENTION_PASS_WITH_CANDIDATE_DEBT,
        RETENTION_PASS_WITH_REFERENCE_DEBT, FAILED, UNAVAILABLE, ASSESSED_WITH_UNSUITABLE_REFERENCE }
    enum ReferenceDisposition { SUITABLE, UNSUITABLE_REFERENCE, SUITABILITY_FAILED, UNAVAILABLE }

    record RunnerChecks(Evidence referenceExecution, Evidence referenceIdentity, Evidence referenceNativeAuthority,
            Evidence referenceDenseComplete, Evidence candidateExecution, Evidence candidateIdentity,
            Evidence candidateNativeAuthority, Evidence candidateBranch, Evidence candidateSafety) { }
    record Metric(Double value, Evidence evidence) { }
    record Distances(Metric rawP95, Metric rawCertifiedMaximum, Metric finalP95, Metric finalCertifiedMaximum) { }
    record Amplitudes(Metric candidate, Metric reference, String candidateInterval, String referenceInterval,
            String candidateBaseline, String referenceBaseline) { }
    record AbsoluteAmplitude(Metric candidate, Metric reference, Metric target, Metric requiredMinimum,
            String interval, String baseline) { }
    record GeometryOracle(Metric p95, Metric certifiedMaximum, Evidence antiZigZag) { }
    record Input(String sceneId, Strength strength, RunnerChecks runnerChecks, Distances distances,
            boolean supportedBend, boolean absoluteAmplitudeApplicable, Amplitudes amplitudes,
            AbsoluteAmplitude absoluteAmplitude, GeometryOracle referenceGeometry, GeometryOracle candidateGeometry) { }
    record Result(String version, Verdict verdict, Status distanceStatus, ReferenceDisposition referenceDisposition,
            Status amplitudeRetentionStatus, Double amplitudeRetentionRatio, Status candidateAbsoluteQuality,
            Status referenceAbsoluteQuality, Status absoluteQualityDebt, boolean releaseQualified, List<String> reasons) { }

    static final double P = WideCompactScenes.P;

    static Result assess(Input input) {
        if (input == null || input.sceneId() == null || input.strength() == null) {
            return result(Verdict.UNAVAILABLE, Status.UNAVAILABLE, ReferenceDisposition.UNAVAILABLE,
                    Status.UNAVAILABLE, null, Status.UNAVAILABLE, Status.UNAVAILABLE,
                    "missing assessment identity or strength");
        }
        boolean frozenAbsoluteApplicability = "faint-apex".equals(input.sceneId());
        if (input.absoluteAmplitudeApplicable() != frozenAbsoluteApplicability) {
            return result(Verdict.UNAVAILABLE, Status.UNAVAILABLE, ReferenceDisposition.UNAVAILABLE,
                    input.supportedBend() ? Status.UNAVAILABLE : Status.NOT_APPLICABLE, null,
                    Status.UNAVAILABLE, Status.UNAVAILABLE, "absolute-amplitude applicability does not match frozen scene");
        }
        Status required = requiredChecks(input.runnerChecks());
        Status candidateOracle = oracleStatus(input.candidateGeometry(), input.strength());
        Status referenceOracle = oracleStatus(input.referenceGeometry(), input.strength());
        Status distance = distanceStatus(input.distances(), input.strength());
        Status amplitude = input.supportedBend() ? amplitudeStatus(input.amplitudes()) : Status.NOT_APPLICABLE;
        AbsoluteOutcomes absolute = absoluteStatus(input.absoluteAmplitude(), input.absoluteAmplitudeApplicable(), input.amplitudes());
        ReferenceDisposition disposition;
        if (referenceOracle == Status.UNAVAILABLE || candidateOracle == Status.UNAVAILABLE || required == Status.UNAVAILABLE)
            disposition = ReferenceDisposition.UNAVAILABLE;
        else if (referenceOracle == Status.PASS) disposition = ReferenceDisposition.SUITABLE;
        else if ("diffuse-three-strand".equals(input.sceneId()) && candidateOracle == Status.PASS
                && required == Status.PASS) disposition = ReferenceDisposition.UNSUITABLE_REFERENCE;
        else disposition = ReferenceDisposition.SUITABILITY_FAILED;

        var reasons = new java.util.ArrayList<String>();
        if (required == Status.FAILED || candidateOracle == Status.FAILED || amplitude == Status.FAILED) {
            reasons.add("one or more mandatory candidate, reference, fidelity, or retention checks failed");
            return assessed(input, Verdict.FAILED, distance, disposition, amplitude, absolute, reasons);
        }
        if (required == Status.UNAVAILABLE || candidateOracle == Status.UNAVAILABLE || distance == Status.UNAVAILABLE
                || amplitude == Status.UNAVAILABLE || absolute.debt() == Status.UNAVAILABLE
                || disposition == ReferenceDisposition.UNAVAILABLE) {
            reasons.add("required runner evidence or measurement is unavailable");
            return assessed(input, Verdict.UNAVAILABLE, distance, disposition, amplitude, absolute, reasons);
        }
        if (disposition == ReferenceDisposition.SUITABILITY_FAILED) {
            reasons.add("reference fails the independent geometry oracle outside the frozen exception");
            return assessed(input, Verdict.FAILED, distance, disposition, amplitude, absolute, reasons);
        }
        if (distance == Status.FAILED) {
            if (disposition == ReferenceDisposition.UNSUITABLE_REFERENCE) {
                reasons.add("frozen diffuse reference fails independent geometry oracle; measured distance failure retained");
                return assessed(input, Verdict.ASSESSED_WITH_UNSUITABLE_REFERENCE, distance, disposition, amplitude, absolute, reasons);
            }
            reasons.add("measured fidelity distance exceeds its applicable limit");
            return assessed(input, Verdict.FAILED, distance, disposition, amplitude, absolute, reasons);
        }
        if (disposition == ReferenceDisposition.UNSUITABLE_REFERENCE) {
            reasons.add("frozen diffuse reference fails independent geometry oracle; disposition retained");
            return assessed(input, Verdict.ASSESSED_WITH_UNSUITABLE_REFERENCE, distance, disposition, amplitude, absolute, reasons);
        }
        Verdict verdict = switch (absolute.debt()) {
            case FAILED -> absolute.candidate() == Status.FAILED && absolute.reference() == Status.FAILED
                    ? Verdict.RETENTION_PASS_WITH_BASELINE_DEBT
                    : absolute.candidate() == Status.FAILED ? Verdict.RETENTION_PASS_WITH_CANDIDATE_DEBT
                    : Verdict.RETENTION_PASS_WITH_REFERENCE_DEBT;
            default -> Verdict.RETENTION_PASS;
        };
        if (absolute.debt() == Status.FAILED) reasons.add("absolute amplitude outcome remains separately visible");
        return assessed(input, verdict, distance, disposition, amplitude, absolute, reasons);
    }

    private record AbsoluteOutcomes(Status candidate, Status reference, Status debt) { }

    private static Result assessed(Input input, Verdict verdict, Status distance, ReferenceDisposition disposition,
            Status amplitude, AbsoluteOutcomes absolute, List<String> reasons) {
        return new Result(VERSION, verdict, distance, disposition, amplitudeRetentionStatus(input),
                amplitudeRatio(input.amplitudes()), absolute.candidate(), absolute.reference(), absolute.debt(), false,
                List.copyOf(reasons));
    }

    private static Status amplitudeRetentionStatus(Input input) {
        return input.supportedBend() ? amplitudeStatus(input.amplitudes()) : Status.NOT_APPLICABLE;
    }

    private static Result result(Verdict verdict, Status distance, ReferenceDisposition reference, Status amplitude,
            Double ratio, Status candidateAbsolute, Status referenceAbsolute, String reason) {
        return new Result(VERSION, verdict, distance, reference, amplitude, ratio, candidateAbsolute,
                referenceAbsolute, Status.UNAVAILABLE, false, List.of(reason));
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

    private static Status oracleStatus(GeometryOracle oracle, Strength strength) {
        if (oracle == null || strength == null) return Status.UNAVAILABLE;
        Double p95 = valid(oracle.p95()), max = valid(oracle.certifiedMaximum());
        if (p95 == null || max == null) return Status.UNAVAILABLE;
        if (oracle.antiZigZag() == Evidence.FAILED) return Status.FAILED;
        if (oracle.antiZigZag() != Evidence.RUNNER_VALIDATED) return Status.UNAVAILABLE;
        double p95Limit = (strength == Strength.STRONG ? .5 : 1.0) * P;
        return p95 > p95Limit || max > P ? Status.FAILED : Status.PASS;
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

    private static AbsoluteOutcomes absoluteStatus(AbsoluteAmplitude a, boolean applicable, Amplitudes amplitudes) {
        if (!applicable) return new AbsoluteOutcomes(Status.NOT_APPLICABLE, Status.NOT_APPLICABLE, Status.NOT_APPLICABLE);
        if (a == null) return new AbsoluteOutcomes(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.UNAVAILABLE);
        if (amplitudes == null || !same(a.interval(), amplitudes.candidateInterval())
                || !same(a.interval(), amplitudes.referenceInterval()) || !same(a.baseline(), amplitudes.candidateBaseline())
                || !same(a.baseline(), amplitudes.referenceBaseline()))
            return new AbsoluteOutcomes(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.UNAVAILABLE);
        Double candidate = valid(a.candidate()), reference = valid(a.reference()), target = valid(a.target()), minimum = valid(a.requiredMinimum());
        if (candidate == null || reference == null || target == null || minimum == null)
            return new AbsoluteOutcomes(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.UNAVAILABLE);
        Status candidateStatus = candidate >= minimum ? Status.PASS : Status.FAILED;
        Status referenceStatus = reference >= minimum ? Status.PASS : Status.FAILED;
        Status debt = candidateStatus == Status.FAILED || referenceStatus == Status.FAILED ? Status.FAILED : Status.PASS;
        return new AbsoluteOutcomes(candidateStatus, referenceStatus, debt);
    }

    private static Double valid(Metric metric) {
        if (metric == null || metric.evidence() != Evidence.RUNNER_VALIDATED || metric.value() == null
                || !Double.isFinite(metric.value()) || metric.value() < 0) return null;
        return metric.value();
    }

    private static boolean same(String a, String b) { return a != null && !a.isBlank() && a.equals(b); }

    private WideCompactRetentionAssessment() { }
}
