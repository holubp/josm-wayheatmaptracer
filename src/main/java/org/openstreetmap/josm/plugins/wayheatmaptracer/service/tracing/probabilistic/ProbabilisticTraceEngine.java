package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ImageOrientationDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.Accounted;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngineRun;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;

/** Standalone B engine using full scalar profiles and exact finite-state longitudinal inference. */
public final class ProbabilisticTraceEngine implements GuidedProbabilisticTraceEngine {
    /** Caller-owned capability boundary for B's experimental weak-signal semantics. */
    public enum ReliabilityPolicy { DIRECT_LONGITUDINAL_V2, BASELINE }

    private final String fieldName;
    private final EvidenceModelParameters parameters;
    private final ReliabilityPolicy reliabilityPolicy;

    /** Creates B for one named scalar field and the normative v0 parameter set. */
    public ProbabilisticTraceEngine(String fieldName) {
        this(fieldName, EvidenceModelParameters.defaults(),
                ReliabilityPolicy.DIRECT_LONGITUDINAL_V2);
    }

    /** Creates B for one named scalar field and explicit versioned parameters. */
    public ProbabilisticTraceEngine(String fieldName, EvidenceModelParameters parameters) {
        this(fieldName, parameters, ReliabilityPolicy.DIRECT_LONGITUDINAL_V2);
    }

    /** Creates a probabilistic engine with an explicit reliability capability. */
    public ProbabilisticTraceEngine(String fieldName, EvidenceModelParameters parameters,
            ReliabilityPolicy reliabilityPolicy) {
        if (fieldName == null || fieldName.isBlank() || parameters == null
                || reliabilityPolicy == null) {
            throw new IllegalArgumentException("Probabilistic engine configuration is incomplete");
        }
        this.fieldName = fieldName;
        this.parameters = parameters;
        this.reliabilityPolicy = reliabilityPolicy;
    }

    @Override
    public TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        return traceWithUsage(request, evidence, network, cancellation).result();
    }

    @Override
    public TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        try {
            return traceWithUsage(request, evidence, network, cancellation, owner).value();
        } finally {
            owner.close();
        }
    }

    @Override
    public Accounted<TraceEngineRun> traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner attemptOwner) {
        return traceAccounted(request, evidence, network, null, cancellation, attemptOwner);
    }

    @Override
    public TraceEngineRun traceGuidedWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, ProbabilisticStructuralGuide guide,
            CancellationProbe cancellation) {
        if (guide == null) {
            throw new IllegalArgumentException("Structural guide is required");
        }
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        try {
            return traceGuidedWithUsage(request, evidence, network, guide, cancellation, owner).value();
        } finally {
            owner.close();
        }
    }

    @Override
    public Accounted<TraceEngineRun> traceGuidedWithUsage(TraceRequest request,
            EvidenceSnapshot evidence, NetworkSnapshot network, ProbabilisticStructuralGuide guide,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner attemptOwner) {
        if (guide == null) {
            throw new IllegalArgumentException("Structural guide is required");
        }
        return traceAccounted(request, evidence, network, guide, cancellation, attemptOwner);
    }

    private Accounted<TraceEngineRun> traceAccounted(TraceRequest request,
            EvidenceSnapshot evidence, NetworkSnapshot network, ProbabilisticStructuralGuide guide,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner attemptOwner) {
        if (attemptOwner == null) {
            throw new IllegalArgumentException("Attempt memory owner is required");
        }
        AttemptMemoryLedger.Owner engineOwner = attemptOwner.child(
                guide == null ? "probabilistic-engine" : "guided-probabilistic-engine");
        AttemptMemoryLedger.Owner candidateOwner = engineOwner.child("candidate-result");
        AttemptMemoryLedger.Owner workingOwner = engineOwner.child("probabilistic-working-set");
        EngineWork work = new EngineWork();
        try {
            TraceEngineRun result = traceInternal(request, evidence, network, cancellation,
                    guide, candidateOwner, workingOwner, work);
            closeIfOpen(workingOwner, null);
            candidateOwner.transferTo(engineOwner);
            candidateOwner.close();
            return new Accounted<>(result, engineOwner);
        } catch (java.util.concurrent.CancellationException exception) {
            closeIfOpen(workingOwner, exception);
            closeIfOpen(candidateOwner, exception);
            try {
                return nativeFailure(request, TraceHypothesisSet.Status.CANCELLED,
                        "cancelled", work, engineOwner);
            } catch (ProbabilisticInference.MemoryLimit diagnosticFailure) {
                closeIfOpen(engineOwner, exception);
                exception.addSuppressed(diagnosticFailure);
                throw exception;
            }
        } catch (LongitudinalModeReliability.ResourceLimitException exception) {
            return resourceFailure(request, exception.getMessage(), "sampled profiles",
                    null, work, engineOwner, candidateOwner, workingOwner);
        } catch (LocalScalarProfileExtractor.ResourceLimitException exception) {
            return resourceFailure(request, exception.getMessage(), "sampled profiles",
                    ledgerCause(exception), work, engineOwner, candidateOwner, workingOwner);
        } catch (ImageOrientationDescriptor.ResourceLimitException exception) {
            return resourceFailure(request, exception.getMessage(), "orientation descriptor",
                    ledgerCause(exception), work, engineOwner, candidateOwner, workingOwner);
        } catch (ProbabilisticInference.MemoryLimit exception) {
            return resourceFailure(request, exception.getMessage(), exception.stage(),
                    exception.limitCause(), work, engineOwner, candidateOwner, workingOwner);
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            return resourceFailure(request, exception.getMessage(), "attempt memory",
                    exception, work, engineOwner, candidateOwner, workingOwner);
        } catch (TraceMemoryLimitException exception) {
            Throwable cause = exception.getCause();
            if (!(cause instanceof AttemptMemoryLedger.ResourceLimitException limit)) throw exception;
            work.pairVisits = exception.pairVisits();
            work.transitions = exception.transitions();
            return resourceFailure(request, exception.getMessage(), exception.stage(), limit,
                    work, engineOwner, candidateOwner, workingOwner);
        } catch (RuntimeException | Error exception) {
            closeIfOpen(workingOwner, exception);
            closeIfOpen(candidateOwner, exception);
            closeIfOpen(engineOwner, exception);
            throw exception;
        }
    }

    private Accounted<TraceEngineRun> resourceFailure(TraceRequest request, String explanation,
            String stage, AttemptMemoryLedger.ResourceLimitException originalLimit,
            EngineWork work, AttemptMemoryLedger.Owner engineOwner,
            AttemptMemoryLedger.Owner candidateOwner, AttemptMemoryLedger.Owner workingOwner) {
        closeIfOpen(workingOwner, null);
        closeIfOpen(candidateOwner, null);
        try {
            return nativeFailure(request, TraceHypothesisSet.Status.RESOURCE_LIMIT,
                    explanation, work, engineOwner);
        } catch (ProbabilisticInference.MemoryLimit diagnosticFailure) {
            AttemptMemoryLedger.ResourceLimitException refusal = diagnosticFailure.limitCause();
            closeIfOpen(engineOwner, diagnosticFailure);
            Throwable cause = originalLimit == null ? refusal : originalLimit;
            if (originalLimit != null) cause.addSuppressed(refusal);
            throw new TraceMemoryLimitException(stage, work.pairVisits, work.transitions,
                    engineOwner.currentBytes(), engineOwner.peakBytes(), refusal.limitBytes(),
                    cause);
        }
    }

    private static AttemptMemoryLedger.ResourceLimitException ledgerCause(RuntimeException exception) {
        Throwable cause = exception.getCause();
        if (!(cause instanceof AttemptMemoryLedger.ResourceLimitException limit)) {
            throw exception;
        }
        return limit;
    }

    private static void closeIfOpen(AttemptMemoryLedger.Owner owner, Throwable primary) {
        if (owner.isClosed()) return;
        try {
            owner.close();
        } catch (RuntimeException | Error closeFailure) {
            if (primary != null) primary.addSuppressed(closeFailure);
            else throw closeFailure;
        }
    }

    private TraceEngineRun traceInternal(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, CancellationProbe cancellation,
        ProbabilisticStructuralGuide guide, AttemptMemoryLedger.Owner candidateOwner,
        AttemptMemoryLedger.Owner workingOwner, EngineWork work) {
        long started = System.nanoTime();
        cancellation.checkpoint();
        validateSnapshots(request, evidence, network);
        ScalarEvidenceField field = evidence.fields().get(fieldName);
        if (field == null) {
            workingOwner.close();
            TraceHypothesisSet unavailable = emptySet(request,
                    TraceHypothesisSet.Status.NO_ROUTE,
                    "scalar evidence field is unavailable", 0, 0, candidateOwner);
            return accountedRun(unavailable, 0, 0, candidateOwner);
        }
        AttemptMemoryLedger.Owner sourceOwner = workingOwner.child("selected-source");
        List<MetricPoint> source = selectedPolyline(request, evidence, network, sourceOwner);
        boolean fixedEndpoints = request.permissions().junctionPolicy() == JunctionPolicy.FIXED;
        long profileSamplingStarted = System.nanoTime();
        List<ProbabilisticProfile> profiles = new ProbabilisticProfileFactory().create(source,
            request.profileChainage(), request.permissions().ordinaryRadiusMeters(), fixedEndpoints,
            evidence, field, parameters, cancellation,
            reliabilityPolicy == ReliabilityPolicy.DIRECT_LONGITUDINAL_V2, workingOwner);
        sourceOwner.close();
        long profileSamplingNanos = System.nanoTime() - profileSamplingStarted;
        logReliability(profiles);
        if (parameters.orientationWeight() > 0.0
            && profiles.stream().anyMatch(ProbabilisticProfile::orientationResourceLimited)) {
            TraceHypothesisSet limited = emptySet(request,
                    TraceHypothesisSet.Status.RESOURCE_LIMIT,
                    "orientation descriptor resource limit", 0, 0, candidateOwner);
            workingOwner.close();
            return accountedRun(limited, 0, 0, candidateOwner);
        }
        long stateObservationStarted = System.nanoTime();
        List<InferenceProfile> evaluated = ProbabilisticInference.allocated(workingOwner,
                ProbabilisticInference.listBytes(profiles.size()), "evaluated profiles",
                () -> new ArrayList<>(profiles.size()));
        long stateCount = 0;
        int minimumStates = Integer.MAX_VALUE;
        int maximumStates = 0;
        ProbabilisticStateBuilder stateBuilder = new ProbabilisticStateBuilder();
        ProbabilisticObservationModel observationModel = new ProbabilisticObservationModel();
        for (ProbabilisticProfile profile : profiles) {
            cancellation.checkpoint();
            AttemptMemoryLedger.Owner evaluatedOwner = workingOwner.child(
                    "evaluated-profile-" + profile.profileIndex());
            AttemptMemoryLedger.Owner latticeOwner = evaluatedOwner.child("state-lattice");
            StateSpaceBuildResult stateResult = stateBuilder.build(profile,
                request.budgets().maximumStatesPerProfile(), latticeOwner);
            if (stateResult.status() == StateSpaceBuildResult.Status.STATE_LIMIT) {
                TraceHypothesisSet limited = emptySet(request,
                        TraceHypothesisSet.Status.RESOURCE_LIMIT,
                        "STATE_LIMIT at profile " + profile.profileIndex() + ": "
                            + stateResult.explanation(), stateCount, 0, candidateOwner);
                workingOwner.close();
                return accountedRun(limited, 0, 0, candidateOwner);
            }
            ProbabilisticStateLattice lattice = stateResult.lattice().orElseThrow();
            stateCount += lattice.cells().size();
            work.stateCount = stateCount;
            minimumStates = Math.min(minimumStates, lattice.cells().size());
            maximumStates = Math.max(maximumStates, lattice.cells().size());
            AttemptMemoryLedger.Owner baselineOwner = evaluatedOwner.child("baseline-observation");
            InferenceProfile evaluatedProfile = observationModel.evaluate(profile, lattice, parameters,
                    reliabilityPolicy == ReliabilityPolicy.DIRECT_LONGITUDINAL_V2, baselineOwner);
            if (guide == null) {
                baselineOwner.transferTo(evaluatedOwner);
                evaluated.add(evaluatedProfile);
            } else {
                AttemptMemoryLedger.Owner guidedOwner = evaluatedOwner.child("guided-observation");
                InferenceProfile guided = guide.apply(evaluatedProfile,
                        profile.sourcePitchMeters(), guidedOwner);
                baselineOwner.close();
                guidedOwner.transferTo(evaluatedOwner);
                evaluated.add(guided);
            }
        }
        long stateObservationNanos = System.nanoTime() - stateObservationStarted;
        long inferenceStarted = System.nanoTime();
        ProbabilisticInferenceResult inference = new ProbabilisticInference().solve(evaluated,
            parameters, request.budgets(), evidence.decisionRegion(), cancellation, workingOwner);
        work.pairVisits = inference.evaluatedPairVisits();
        work.transitions = inference.evaluatedTransitions();
        work.rawAlternatives = inference.rawPaths().size();
        long inferenceNanos = System.nanoTime() - inferenceStarted;
        boolean usableRoutes = inference.status() == ProbabilisticInferenceResult.Status.COMPLETE
                || inference.status() == ProbabilisticInferenceResult.Status.AMBIGUOUS
                || inference.status() == ProbabilisticInferenceResult.Status.REVIEW_REQUIRED;
        long materializationStarted = System.nanoTime();
        List<TraceHypothesis> hypotheses = usableRoutes
                ? accountedHypotheses(inference, profiles, evaluated, evidence, guide,
                    candidateOwner, workingOwner) : List.of();
        long materializationNanos = System.nanoTime() - materializationStarted;
        TraceHypothesisSet.Status status = switch (inference.status()) {
            case COMPLETE -> TraceHypothesisSet.Status.COMPLETE;
            case AMBIGUOUS, REVIEW_REQUIRED -> TraceHypothesisSet.Status.AMBIGUOUS;
            case ALL_MISSING, NO_ROUTE, NUMERIC_FAILURE -> TraceHypothesisSet.Status.NO_ROUTE;
            case RESOURCE_LIMIT -> TraceHypothesisSet.Status.RESOURCE_LIMIT;
        };
        TraceHypothesisSet result = accountedSet(candidateOwner, workingOwner, hypotheses,
                status, inference.alternativeSearchTruncated(), stateCount,
                inference.evaluatedTransitions(), guide == null ? inference.explanation()
                    : inference.explanation() + "; capped same-image structural guide applied");
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.profileMs", millis(profileSamplingNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.stateObservationMs", millis(stateObservationNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.inferenceMs", millis(inferenceNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.materializeMs", millis(materializationNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.totalMs", millis(System.nanoTime() - started));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.profiles", profiles.size());
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.states", stateCount);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.minStates", minimumStates == Integer.MAX_VALUE ? 0 : minimumStates);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.maxStates", maximumStates);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.hypotheses", hypotheses.size());
        TraceEngineRun run = accountedRun(result, inference.evaluatedPairVisits(),
                inference.rawPaths().size(), candidateOwner);
        workingOwner.close();
        return run;
    }

    private void logReliability(List<ProbabilisticProfile> profiles) {
        int count = 0;
        double minimumAmplitude = 1.0, totalAmplitude = 0.0, maximumAmplitude = 0.0;
        double minimumCorroboration = 1.0, totalCorroboration = 0.0, maximumCorroboration = 0.0;
        double minimumPosition = 1.0, totalPosition = 0.0, maximumPosition = 0.0;
        for (ProbabilisticProfile profile : profiles) {
            for (ProbabilisticProfile.Mode mode : profile.modes()) {
                count++;
                minimumAmplitude = Math.min(minimumAmplitude, mode.scalarAmplitudeReliability());
                totalAmplitude += mode.scalarAmplitudeReliability();
                maximumAmplitude = Math.max(maximumAmplitude, mode.scalarAmplitudeReliability());
                minimumCorroboration = Math.min(minimumCorroboration, mode.branchCoherence());
                totalCorroboration += mode.branchCoherence();
                maximumCorroboration = Math.max(maximumCorroboration, mode.branchCoherence());
                minimumPosition = Math.min(minimumPosition, mode.positionalReliability());
                totalPosition += mode.positionalReliability();
                maximumPosition = Math.max(maximumPosition, mode.positionalReliability());
            }
        }
        if (count == 0) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "reliability.modes", 0);
            return;
        }
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.modes", count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.amplitudeMin", minimumAmplitude);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.amplitudeMean", totalAmplitude / count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.amplitudeMax", maximumAmplitude);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.corroborationMin", minimumCorroboration);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.corroborationMean", totalCorroboration / count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.corroborationMax", maximumCorroboration);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.positionMin", minimumPosition);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.positionMean", totalPosition / count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.positionMax", maximumPosition);
    }

    private List<TraceHypothesis> accountedHypotheses(ProbabilisticInferenceResult inference,
            List<ProbabilisticProfile> sampledProfiles, List<InferenceProfile> profiles,
            EvidenceSnapshot evidence, ProbabilisticStructuralGuide guide,
            AttemptMemoryLedger.Owner resultOwner, AttemptMemoryLedger.Owner workingOwner) {
        int hypothesisCount = inference.distinctPaths().size();
        int diagnosticCount = guide == null ? 15 : 20;
        long outputBytes = ProbabilisticInference.listBytes(hypothesisCount);
        long temporaryBytes = ProbabilisticInference.listBytes(hypothesisCount) * 3L
                + mapBytes(hypothesisCount);
        for (ProbabilisticPath path : inference.distinctPaths()) {
            outputBytes = Math.addExact(outputBytes, AttemptMemoryLedger.objectBytes(88));
            outputBytes = Math.addExact(outputBytes,
                    ProbabilisticInference.listBytes(path.points().size()));
            outputBytes = Math.addExact(outputBytes, mapBytes(diagnosticCount));
            temporaryBytes = Math.addExact(temporaryBytes,
                    ProbabilisticInference.listBytes(path.points().size()));
            temporaryBytes = Math.addExact(temporaryBytes, mapBytes(diagnosticCount));
        }
        AttemptMemoryLedger.Owner temporaryOwner = workingOwner.child("hypothesis-builders");
        AttemptMemoryLedger.Reservation output = reserve(resultOwner, outputBytes,
                "engine result materialization");
        AttemptMemoryLedger.Reservation temporary = reserve(temporaryOwner, temporaryBytes,
                "engine result materialization");
        try {
            for (ProbabilisticPath path : inference.distinctPaths()) {
                resultOwner.retain(path.points());
                for (MetricPoint point : path.points()) resultOwner.retain(point);
            }
            List<TraceHypothesis> hypotheses = toHypotheses(inference, sampledProfiles,
                    profiles, evidence, guide);
            output.adopt(hypotheses);
            return hypotheses;
        } catch (RuntimeException | Error exception) {
            output.close();
            throw exception;
        } finally {
            temporary.close();
            temporaryOwner.close();
        }
    }

    private static TraceHypothesisSet accountedSet(AttemptMemoryLedger.Owner resultOwner,
            AttemptMemoryLedger.Owner workingOwner, List<TraceHypothesis> hypotheses,
            TraceHypothesisSet.Status status, boolean alternativesTruncated, long stateCount,
            long transitions, String explanation) {
        AttemptMemoryLedger.Reservation output = reserve(resultOwner,
                AttemptMemoryLedger.objectBytes(64), "engine result materialization");
        AttemptMemoryLedger.Owner validationOwner = workingOwner.child("result-validation");
        AttemptMemoryLedger.Reservation validation = reserve(validationOwner,
                mapBytes(hypotheses.size()) * 2L, "engine result materialization");
        try {
            TraceHypothesisSet result = new TraceHypothesisSet(TrackerMode.PROBABILISTIC,
                    hypotheses, status, alternativesTruncated, stateCount, transitions,
                    explanation);
            output.adopt(result);
            return result;
        } catch (RuntimeException | Error exception) {
            output.close();
            throw exception;
        } finally {
            validation.close();
            validationOwner.close();
        }
    }

    private static AttemptMemoryLedger.Reservation reserve(AttemptMemoryLedger.Owner owner,
            long bytes, String stage) {
        try {
            return owner.reserve(bytes);
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            throw new ProbabilisticInference.MemoryLimit(stage, exception);
        }
    }

    private static long mapBytes(long entries) {
        return AttemptMemoryLedger.objectBytes(48)
                + AttemptMemoryLedger.referenceArrayBytes(hashTableCapacity(entries))
                + Math.multiplyExact(entries, AttemptMemoryLedger.objectBytes(48));
    }

    private static long hashTableCapacity(long entries) {
        long required = Math.max(16L, Math.multiplyExact(entries, 2L));
        long capacity = 16L;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2L);
        return capacity;
    }

    private List<TraceHypothesis> toHypotheses(ProbabilisticInferenceResult inference,
        List<ProbabilisticProfile> sampledProfiles, List<InferenceProfile> profiles,
        EvidenceSnapshot evidence, ProbabilisticStructuralGuide guide) {
        List<TraceHypothesis> result = new ArrayList<>();
        List<ProbabilisticPath> distinctPaths = inference.distinctPaths();
        List<String> exportedBranches = exportedBranchSignatures(distinctPaths);
        int index = 0;
        for (ProbabilisticPath path : distinctPaths) {
            List<ObservationOwnership> support = supportOnFinalCurve(path, sampledProfiles, profiles, evidence);
            Map<String, Double> diagnostics = new LinkedHashMap<>();
            diagnostics.put("logPartition", inference.logPartition());
            diagnostics.put("logBaseMeasure", path.logBaseMeasure());
            diagnostics.put("measuredMeters", inference.gapSummary().measuredMeters());
            diagnostics.put("absentMeters", inference.gapSummary().absentMeters());
            diagnostics.put("longestInternalGapMeters", inference.gapSummary().longestInternalGapMeters());
            diagnostics.put("temperature", parameters.temperature());
            diagnostics.put("turnWeight", parameters.turnWeight());
            diagnostics.put("orientationWeight", parameters.orientationWeight());
            var completion = inference.completion().orElseThrow();
            diagnostics.put("bTerminalCompletionPolicyV1", 1.0);
            diagnostics.put("effectiveRawAlternativeLimit", (double) completion.effectiveRawLimit());
            diagnostics.put("effectiveDistinctAlternativeLimit", (double) completion.effectiveDistinctLimit());
            diagnostics.put("completePathsAtSaturation", (double) completion.completePathsAtSaturation());
            diagnostics.put("terminalCountSaturated", completion.terminalCountSaturated() ? 1.0 : 0.0);
            diagnostics.put("rawEnumerationCapped", completion.rawEnumerationCapped() ? 1.0 : 0.0);
            diagnostics.put("requestedDiversityReached", completion.requestedDiversityReached() ? 1.0 : 0.0);
            if (guide != null) {
                diagnostics.put("structuralGuideApplied", 1.0);
                diagnostics.put("structuralGuideWeight", parameters.guideWeight());
                diagnostics.put("structuralGuideCap", ProbabilisticStructuralGuide.MAXIMUM_COST);
                diagnostics.put("structuralGuideSameImage", 1.0);
                diagnostics.put("structuralGuideSectionCount", (double) guide.sectionCount());
            }
            String exportedBranch = exportedBranches.get(index);
            result.add(new TraceHypothesis("probabilistic-" + index++, exportedBranch,
                path.points(), support, path.energy(), path.conditionalPosteriorMass(), diagnostics));
        }
        return List.copyOf(result);
    }

    private static List<ObservationOwnership> supportOnFinalCurve(ProbabilisticPath path,
        List<ProbabilisticProfile> sampledProfiles, List<InferenceProfile> profiles,
        EvidenceSnapshot evidence) {
        List<MetricPoint> points = path.points();
        int[] states = path.stateIndices();
        List<ObservationOwnership> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            MetricPoint point = points.get(index);
            InferenceProfile evaluated = profiles.get(index);
            ObservationOwnership profileOwnership = evaluated.ownership();
            if (!evidence.routePositionAuthorized(point)) {
                result.add(ObservationOwnership.NO_RASTER);
            } else if (profileOwnership == ObservationOwnership.CORE_CENSORED
                    || profileOwnership == ObservationOwnership.SHOULDER_CENSORED
                    || profileOwnership == ObservationOwnership.NO_RASTER
                    || profileOwnership == ObservationOwnership.NO_SIGNAL_VALID_RASTER) {
                result.add(profileOwnership);
            } else if (directlyOwnedByMeasuredMode(sampledProfiles.get(index), evaluated,
                    states[index])) {
                result.add(ObservationOwnership.DIRECT_TWO_SIDED);
            } else {
                result.add(ObservationOwnership.INFERRED_GAP);
            }
        }
        return List.copyOf(result);
    }

    static List<String> exportedBranchSignatures(List<ProbabilisticPath> paths) {
        if (paths == null) {
            throw new IllegalArgumentException("Exported alternatives are missing");
        }
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<String> result = new ArrayList<>(paths.size());
        for (ProbabilisticPath path : paths) {
            int occurrence = occurrences.merge(path.branchSignature(), 1, Integer::sum) - 1;
            result.add(occurrence == 0 ? path.branchSignature()
                    : path.branchSignature() + "#alternative-" + occurrence);
        }
        return List.copyOf(result);
    }

    private static boolean directlyOwnedByMeasuredMode(ProbabilisticProfile sampled,
            InferenceProfile evaluated, int stateIndex) {
        LateralStateCell cell = evaluated.cells().get(stateIndex);
        double offset = cell.offsetMeters();
        double tolerance = 1e-9 * Math.max(1.0, sampled.sourcePitchMeters());
        return sampled.modes().stream().anyMatch(mode ->
                mode.id().equals(cell.branchLabel())
                    && offset + tolerance >= mode.coreMinimumMeters()
                    && offset - tolerance <= mode.coreMaximumMeters());
    }

    private static List<MetricPoint> selectedPolyline(TraceRequest request,
        EvidenceSnapshot evidence, NetworkSnapshot network, AttemptMemoryLedger.Owner owner) {
        DetachedPrimitive primitive = network.primitives().get(request.selectedWayKey());
        if (!(primitive instanceof DetachedWay way)
            || request.selectedRange().lastIndex() >= way.nodeKeys().size()
            || request.selectedRange().size() < 2) {
            throw new IllegalArgumentException("Selected occurrence range is absent from the network snapshot");
        }
        AttemptMemoryLedger.Owner builder = owner.child("source-list-builder");
        int pointCount = request.selectedRange().size();
        List<MetricPoint> result = ProbabilisticInference.allocated(builder,
                ProbabilisticInference.listBytes(pointCount),
                "sampled profiles", () -> new ArrayList<>(pointCount));
        for (int index = request.selectedRange().firstIndex(); index <= request.selectedRange().lastIndex(); index++) {
            PrimitiveKey nodeKey = way.nodeKeys().get(index);
            DetachedPrimitive nodePrimitive = network.primitives().get(nodeKey);
            if (!(nodePrimitive instanceof DetachedNode node)) {
                throw new IllegalArgumentException("Selected way node is absent from the network snapshot");
            }
            result.add(ProbabilisticInference.allocated(builder,
                    AttemptMemoryLedger.objectBytes(16), "sampled profiles",
                    () -> evidence.coordinateFrame().toMetric(node.coordinate())));
        }
        try {
            List<MetricPoint> retained = ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(result.size()), "sampled profiles",
                    () -> List.copyOf(result));
            for (MetricPoint point : result) owner.retain(point);
            return retained;
        } finally {
            builder.close();
        }
    }

    private static void validateSnapshots(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network) {
        if (request == null || evidence == null || network == null
            || request.engine() != TrackerMode.PROBABILISTIC
            || !request.evidenceSnapshotId().equals(evidence.snapshotId())
            || !request.evidenceContentHash().equals(evidence.canonicalHash())
            || !request.networkSnapshotId().equals(network.snapshotId())
            || !request.networkContentHash().equals(network.canonicalHash())
            || !request.evidenceResolution().equals(evidence.resolution())
            || network.role() != org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole.CAPTURED_BEFORE) {
            throw new IllegalArgumentException("Probabilistic request does not match immutable snapshots");
        }
    }

    private static long millis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static TraceEngineRun run(TraceHypothesisSet result,
            long pairVisits, int rawAlternatives) {
        return new TraceEngineRun(result, new TraceWorkUsage(pairVisits,
            result.evaluatedTransitions(), rawAlternatives, result.hypotheses().size()));
    }

    private static TraceEngineRun accountedRun(TraceHypothesisSet result,
            long pairVisits, int rawAlternatives, AttemptMemoryLedger.Owner owner) {
        return ProbabilisticInference.allocated(owner,
                AttemptMemoryLedger.objectBytes(24) + AttemptMemoryLedger.objectBytes(32),
                "engine result", () -> {
                    TraceWorkUsage usage = new TraceWorkUsage(pairVisits,
                            result.evaluatedTransitions(), rawAlternatives,
                            result.hypotheses().size());
                    return new TraceEngineRun(result, usage, owner.peakBytes());
                });
    }

    private static Accounted<TraceEngineRun> nativeFailure(TraceRequest request,
            TraceHypothesisSet.Status status, String explanation, EngineWork work,
            AttemptMemoryLedger.Owner engineOwner) {
        AttemptMemoryLedger.Owner diagnostic = engineOwner.child("native-failure-result");
        try {
            TraceEngineRun run = failureRun(request, status, explanation, work, diagnostic);
            diagnostic.transferTo(engineOwner);
            diagnostic.close();
            return new Accounted<>(run, engineOwner);
        } catch (RuntimeException | Error exception) {
            closeIfOpen(diagnostic, exception);
            throw exception;
        }
    }

    private static TraceEngineRun failureRun(TraceRequest request,
            TraceHypothesisSet.Status status, String explanation, EngineWork work,
            AttemptMemoryLedger.Owner owner) {
        TraceHypothesisSet result = ProbabilisticInference.allocated(owner,
                AttemptMemoryLedger.objectBytes(64),
                "engine failure result", () -> new TraceHypothesisSet(request.engine(), List.of(),
                    status, false, work.stateCount, work.transitions, explanation));
        return ProbabilisticInference.allocated(owner,
                AttemptMemoryLedger.objectBytes(24) + AttemptMemoryLedger.objectBytes(32),
                "engine failure result", () -> {
                    TraceWorkUsage usage = new TraceWorkUsage(work.pairVisits,
                            work.transitions, 0, 0);
                    return new TraceEngineRun(result, usage, owner.peakBytes());
                });
    }

    private static final class EngineWork {
        private long stateCount;
        private long pairVisits;
        private long transitions;
        private int rawAlternatives;
    }

    private static TraceHypothesisSet emptySet(TraceRequest request,
            TraceHypothesisSet.Status status, String explanation, long states, long transitions,
            AttemptMemoryLedger.Owner owner) {
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(64),
                "engine result materialization",
                () -> new TraceHypothesisSet(request.engine(), List.of(), status,
                    false, states, transitions, explanation));
    }
}
