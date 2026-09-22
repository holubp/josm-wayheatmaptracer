package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.GeometricCurvatureOperator.InverseMetersVector;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenSample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Deterministic projected-gradient refitter over one frozen scalar-image objective. */
public final class ImageSupportedRefitter {
    private static final double NORMALIZATION_LENGTH_METERS = 1.0;
    private static final int MAXIMUM_CONTROL_POINTS = 32_768;
    private static final int MAXIMUM_VALIDATORS = 1_024;
    private static final long MAXIMUM_WORKING_BYTES = 256L * 1024L * 1024L;
    /** Why one independent control is held equal to its frozen input coordinate. */
    public enum FreezeReason { EXPLICIT_FIXED, UNAVAILABLE_PROFILE, UNKNOWN_CURVATURE_STENCIL }
    /** Controls whether coordinate refinement is permitted. */
    public enum Mode { OFF, REDUCE_POINTS_ONLY, IMAGE_SUPPORTED }

    /** Truthful numerical outcome; budget exhaustion and failed line search are not convergence. */
    public enum Status {
        SKIPPED_OFF,
        SKIPPED_REDUCE_ONLY,
        SKIPPED_NO_ELIGIBLE_CONTROLS,
        CONVERGED,
        ITERATION_LIMIT_RETAINED,
        LINE_SEARCH_FAILED_RETAINED,
        REVERTED
    }

    /** Immutable projected-gradient parameters in physical units. */
    public record Config(double sourcePitchMeters, double lambdaCurvature, double lambdaTrust,
            double trustRadiusMeters, int maximumIterations, int maximumLineSearchHalvings,
            double armijoCoefficient, double gradientTolerance, double objectiveTolerance,
            double movementToleranceMeters, int stableIterationsRequired) {
        /** Validates deterministic numerical limits. */
        public Config {
            if (!positive(sourcePitchMeters) || !nonnegative(lambdaCurvature) || !nonnegative(lambdaTrust)
                    || !positive(trustRadiusMeters) || maximumIterations < 1
                    || maximumLineSearchHalvings < 1 || !positive(armijoCoefficient)
                    || !positive(gradientTolerance) || !positive(objectiveTolerance)
                    || !positive(movementToleranceMeters) || stableIterationsRequired < 1) {
                throw new IllegalArgumentException("Refit parameters must be finite and bounded");
            }
        }

        /** Returns the plan's initial deterministic settings for a known physical pitch. */
        public static Config defaults(double sourcePitchMeters) {
            return new Config(sourcePitchMeters, 3.0, 0.1, 1.25 * sourcePitchMeters,
                    150, 20, 1.0e-4, 1.0e-5, 1.0e-6, 1.0e-3, 3);
        }

        /** Returns an otherwise identical configuration with a bounded iteration limit. */
        public Config withIterationLimit(int iterations) {
            return new Config(sourcePitchMeters, lambdaCurvature, lambdaTrust, trustRadiusMeters,
                    iterations, maximumLineSearchHalvings, armijoCoefficient, gradientTolerance,
                    objectiveTolerance, movementToleranceMeters, stableIterationsRequired);
        }

        private static boolean positive(double value) {
            return Double.isFinite(value) && value > 0.0;
        }

        private static boolean nonnegative(double value) {
            return Double.isFinite(value) && value >= 0.0;
        }
    }

    /** External whole-geometry validation hook run on every numerical trial. */
    @FunctionalInterface
    public interface GeometryValidator {
        /** Returns whether the complete trial geometry remains feasible. */
        Validation validate(List<MetricPoint> points);
    }

    /** Typed result from one whole-geometry validation hook. */
    public record Validation(boolean feasible, String code) {
        /** Validates a stable nonempty result code. */
        public Validation {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Validation code is required");
            }
        }
        /** Returns a successful validation. */
        public static Validation accepted() {
            return new Validation(true, "ok");
        }

        /** Returns a rejected validation with a stable diagnostic code. */
        public static Validation rejected(String code) {
            return new Validation(false, Objects.requireNonNull(code));
        }
    }

    /** Immutable request including a nonaccumulating trust origin and frozen field source. */
    public record Request(List<MetricPoint> initialPoints, List<MetricPoint> trustOrigin,
            Set<Integer> fixedIndices, ImageCostField image, MetricRegion branchCorridor,
            Mode mode, Config config, List<GeometryValidator> validators) {
        /** Defensively copies request containers and validates occurrence alignment. */
        public Request {
            Objects.requireNonNull(initialPoints, "initialPoints");
            Objects.requireNonNull(trustOrigin, "trustOrigin");
            Objects.requireNonNull(fixedIndices, "fixedIndices");
            Objects.requireNonNull(validators, "validators");
            int controlCount = initialPoints.size();
            if (controlCount < 2 || controlCount > MAXIMUM_CONTROL_POINTS
                    || trustOrigin.size() != controlCount || fixedIndices.size() > controlCount
                    || validators.size() > MAXIMUM_VALIDATORS) {
                throw new IllegalArgumentException("Refit request exceeds deterministic bounds");
            }
            initialPoints = List.copyOf(initialPoints);
            trustOrigin = List.copyOf(trustOrigin);
            fixedIndices = Set.copyOf(fixedIndices);
            validators = List.copyOf(validators);
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(branchCorridor, "branchCorridor");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(config, "config");
            boolean invalidFixedIndex = false;
            for (int index : fixedIndices) {
                invalidFixedIndex |= index < 0 || index >= initialPoints.size();
            }
            if (invalidFixedIndex) {
                throw new IllegalArgumentException("Refit geometry and occurrence policies are inconsistent");
            }
        }

        /** Returns a copy with one additional whole-geometry validator. */
        public Request withValidator(GeometryValidator validator) {
            List<GeometryValidator> copy = new ArrayList<>(validators);
            copy.add(validator);
            return new Request(initialPoints, trustOrigin, fixedIndices, image, branchCorridor, mode, config, copy);
        }
    }

    /**
     * Objective value and analytic projected search gradient in metric coordinates.
     *
     * <p>Coordinates held by frozen equality constraints have zero gradient. The objective can be
     * queried away from that feasible manifold for diagnostics, but those zero components are not
     * raw unconstrained derivatives.</p>
     */
    public record ObjectiveEvaluation(double objective, List<MetricPoint> gradient, boolean supported) {
        /** Copies the gradient. */
        public ObjectiveEvaluation {
            gradient = List.copyOf(gradient);
        }
    }

    /** Immutable result from one invocation. */
    public record Result(Status status, List<MetricPoint> points, double initialObjective,
            double finalObjective, int iterations, double scaledGradientResidual,
            double maximumMovementMeters, boolean acceptedAlternative, List<String> validationCodes) {
        /** Copies result geometry and diagnostics. */
        public Result {
            points = List.copyOf(points);
            validationCodes = List.copyOf(validationCodes);
        }
    }

    /** Frozen objective fields and geometry policies for one optimizer invocation. */
    public static final class FrozenProblem {
        private final Request request;
        private final FrozenRefitMesh mesh;
        private final List<FrozenProfile> profiles;
        private final SupportedCurvatureBank curvatureBank;
        private final List<CurvatureBlock> curvatureBlocks;
        private final Set<Integer> frozenControls;
        private final Map<Integer, Set<FreezeReason>> freezeReasons;
        private final boolean movableEvidence;

        private FrozenProblem(Request request, FrozenRefitMesh mesh, List<FrozenProfile> profiles,
                SupportedCurvatureBank curvatureBank, List<CurvatureBlock> curvatureBlocks,
                Set<Integer> frozenControls, Map<Integer, Set<FreezeReason>> freezeReasons,
                boolean movableEvidence) {
            this.request = request;
            this.mesh = mesh;
            this.profiles = List.copyOf(profiles);
            this.curvatureBank = curvatureBank;
            this.curvatureBlocks = List.copyOf(curvatureBlocks);
            this.frozenControls = Set.copyOf(frozenControls);
            LinkedHashMap<Integer, Set<FreezeReason>> copiedReasons = new LinkedHashMap<>();
            freezeReasons.forEach((index, reasons) -> copiedReasons.put(index, Set.copyOf(reasons)));
            this.freezeReasons = Map.copyOf(copiedReasons);
            this.movableEvidence = movableEvidence;
        }

        /** Returns the immutable physical reference mesh size for diagnostics and bounds tests. */
        public int meshPointCount() { return mesh.meshPointCount(); }
        /** Returns the immutable reference length in metres. */
        public double referenceLengthMeters() { return mesh.referenceLengthMeters(); }
        /** Returns typed image-curvature ownership for every interior mesh point. */
        public List<SupportedCurvatureBank.Target> curvatureTargets() { return curvatureBank.targets(); }
        /** Returns occurrence indices frozen because their own branch evidence is unavailable. */
        public Set<Integer> frozenControlIndices() { return frozenControls; }
        /** Returns immutable equality-constraint provenance for every frozen occurrence. */
        public Map<Integer, Set<FreezeReason>> frozenControlReasons() { return freezeReasons; }
    }

    private record CurvatureBlock(int firstMeshPoint, int meshPointCount,
            GeometricCurvatureOperator operator) { }

    /** Freezes image descriptors, branch identity, arclength and supported-curvature targets. */
    public FrozenProblem freeze(Request request) {
        return freeze(request, CancellationProbe.NONE);
    }

    /** Freezes all branch, mesh, image and curvature evidence with bounded cancellation checks. */
    public FrozenProblem freeze(Request request, CancellationProbe cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        double spacing = Math.min(2.0, Math.max(request.config().sourcePitchMeters() / 2.0, 0.5));
        FrozenRefitMesh mesh = FrozenRefitMesh.build(request.initialPoints(), request.fixedIndices(),
                spacing, cancellation);
        List<MetricPoint> meshPoints = mesh.interpolate(request.initialPoints());
        preflightFrozenProfiles(meshPoints.size(), request.config().sourcePitchMeters());
        ArrayList<FrozenProfile> profiles = new ArrayList<>(meshPoints.size());
        for (int index = 0; index < meshPoints.size(); index++) {
            cancellation.checkpoint();
            MetricPoint tangent = tangent(meshPoints, index);
            profiles.add(request.image().freezeProfile(meshPoints.get(index), tangent, cancellation));
        }
        SupportedCurvatureBank bank = SupportedCurvatureBank.build(mesh, profiles,
                request.config().sourcePitchMeters(), cancellation);
        List<CurvatureBlock> blocks = curvatureBlocks(bank, request.config(), NORMALIZATION_LENGTH_METERS);
        Set<Integer> frozen = new HashSet<>(request.fixedIndices());
        Map<Integer, Set<FreezeReason>> freezeReasons = new LinkedHashMap<>();
        request.fixedIndices().forEach(index -> addFreezeReason(index, FreezeReason.EXPLICIT_FIXED,
                frozen, freezeReasons));
        for (int rowIndex = 0; rowIndex < mesh.rows().size(); rowIndex++) {
            if (profiles.get(rowIndex).support() != FrozenSupport.MEASURED) {
                freezeRow(mesh.rows().get(rowIndex), FreezeReason.UNAVAILABLE_PROFILE,
                        frozen, freezeReasons);
            }
        }
        for (int targetIndex = 0; targetIndex < bank.targets().size(); targetIndex++) {
            if (bank.targets().get(targetIndex).status() == SupportedCurvatureBank.Status.UNKNOWN) {
                freezeRow(mesh.rows().get(targetIndex), FreezeReason.UNKNOWN_CURVATURE_STENCIL,
                        frozen, freezeReasons);
                freezeRow(mesh.rows().get(targetIndex + 1), FreezeReason.UNKNOWN_CURVATURE_STENCIL,
                        frozen, freezeReasons);
                freezeRow(mesh.rows().get(targetIndex + 2), FreezeReason.UNKNOWN_CURVATURE_STENCIL,
                        frozen, freezeReasons);
            }
        }
        boolean movableEvidence = false;
        for (int rowIndex = 0; rowIndex < mesh.rows().size(); rowIndex++) {
            FrozenRefitMesh.Row row = mesh.rows().get(rowIndex);
            if (profiles.get(rowIndex).support() == FrozenSupport.MEASURED
                    && (!frozen.contains(row.leftControl()) || !frozen.contains(row.rightControl()))) {
                movableEvidence = true;
                break;
            }
        }
        return new FrozenProblem(request, mesh, profiles, bank, blocks, frozen, freezeReasons,
                movableEvidence);
    }

    /** Evaluates the frozen arclength-weighted image, curvature and trust objective. */
    public ObjectiveEvaluation evaluate(FrozenProblem problem, List<MetricPoint> points) {
        return evaluate(problem, points, CancellationProbe.NONE);
    }

    /** Evaluates the frozen objective with bounded cooperative cancellation. */
    public ObjectiveEvaluation evaluate(FrozenProblem problem, List<MetricPoint> points,
            CancellationProbe cancellation) {
        if (problem == null || points == null || points.size() != problem.request.initialPoints().size()) {
            throw new IllegalArgumentException("Objective geometry occurrence count changed");
        }
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.checkpoint();
        List<MetricPoint> meshPoints = problem.mesh.interpolate(points);
        int meshSize = meshPoints.size();
        double[] meshGx = new double[meshSize];
        double[] meshGy = new double[meshSize];
        double objective = 0.0;
        double referenceLength = NORMALIZATION_LENGTH_METERS;
        for (int segment = 0; segment < meshSize - 1; segment++) {
            cancellation.checkpoint();
            FrozenProfile firstProfile = problem.profiles.get(segment);
            FrozenProfile secondProfile = problem.profiles.get(segment + 1);
            if (firstProfile.support() != FrozenSupport.MEASURED
                    || secondProfile.support() != FrozenSupport.MEASURED) {
                continue;
            }
            MetricPoint start = meshPoints.get(segment);
            MetricPoint end = meshPoints.get(segment + 1);
            MetricPoint delta = subtract(end, start);
            double length = Math.hypot(delta.xMeters(), delta.yMeters());
            if (!(length > 1.0e-9)) {
                return unsupported(points.size());
            }
            Optional<FrozenSample> first = firstProfile.evaluate(start);
            Optional<FrozenSample> second = secondProfile.evaluate(end);
            if (first.isEmpty() || second.isEmpty()) return unsupported(points.size());
            FrozenSample a = first.orElseThrow();
            FrozenSample b = second.orElseThrow();
            double reliability = Math.sqrt(firstProfile.positionalReliability()
                    * secondProfile.positionalReliability());
            double density = reliability * 0.5 * (a.cost() + b.cost());
            double ex = delta.xMeters();
            double ey = delta.yMeters();
            double expected = averageUndirected(firstProfile.orientationRadians(),
                    secondProfile.orientationRadians());
            double ux = Math.cos(expected);
            double uy = Math.sin(expected);
            double cross = ex * uy - ey * ux;
            double certainty = reliability * Math.min(firstProfile.orientationCertainty(),
                    secondProfile.orientationCertainty());
            double orientationNumerator = 0.5 * certainty * cross * cross;
            objective += (length * density + orientationNumerator / length) / referenceLength;
            double commonX = (ex / length * density
                    + certainty * cross * uy / length
                    - orientationNumerator * ex / (length * length * length)) / referenceLength;
            double commonY = (ey / length * density
                    - certainty * cross * ux / length
                    - orientationNumerator * ey / (length * length * length)) / referenceLength;
            meshGx[segment] += -commonX + reliability * 0.5 * length * a.gradientX() / referenceLength;
            meshGy[segment] += -commonY + reliability * 0.5 * length * a.gradientY() / referenceLength;
            meshGx[segment + 1] += commonX + reliability * 0.5 * length * b.gradientX() / referenceLength;
            meshGy[segment + 1] += commonY + reliability * 0.5 * length * b.gradientY() / referenceLength;
        }

        for (CurvatureBlock block : problem.curvatureBlocks) {
            cancellation.checkpoint();
            int count = block.meshPointCount();
            GeometricCurvatureOperator.Evaluation value = block.operator.evaluate(
                    meshPoints.subList(block.firstMeshPoint(), block.firstMeshPoint() + count));
            objective += value.objective();
            for (int local = 0; local < value.gradient().size(); local++) {
                int global = block.firstMeshPoint() + local;
                meshGx[global] += value.gradient().get(local).xObjectivePerMeter();
                meshGy[global] += value.gradient().get(local).yObjectivePerMeter();
            }
        }

        List<MetricPoint> meshGradient = new ArrayList<>(meshSize);
        for (int index = 0; index < meshSize; index++) {
            meshGradient.add(new MetricPoint(meshGx[index], meshGy[index]));
        }
        List<MetricPoint> controlGradient = problem.mesh.scatter(meshGradient);
        int size = points.size();
        double[] gx = controlGradient.stream().mapToDouble(MetricPoint::xMeters).toArray();
        double[] gy = controlGradient.stream().mapToDouble(MetricPoint::yMeters).toArray();

        for (int index = 0; index < size; index++) {
            if ((index & 1023) == 0) cancellation.checkpoint();
            MetricPoint delta = subtract(points.get(index), problem.request.trustOrigin().get(index));
            double distance = Math.hypot(delta.xMeters(), delta.yMeters());
            double radius = problem.request.config().trustRadiusMeters();
            double normalized = distance / radius;
            double rho = normalized <= 1.0 ? 0.5 * normalized * normalized : normalized - 0.5;
            objective += problem.request.config().lambdaTrust() * rho;
            if (distance > 1.0e-12) {
                double derivative = problem.request.config().lambdaTrust()
                        * (normalized <= 1.0 ? normalized : 1.0) / radius / distance;
                gx[index] += derivative * delta.xMeters();
                gy[index] += derivative * delta.yMeters();
            }
        }
        List<MetricPoint> gradient = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            gradient.add(problem.frozenControls.contains(index)
                    ? new MetricPoint(0, 0) : new MetricPoint(gx[index], gy[index]));
        }
        return new ObjectiveEvaluation(objective, gradient, true);
    }

    /** Runs projected gradient with fixed diagonal scaling and Armijo backtracking. */
    public Result refit(Request request) {
        return refit(request, CancellationProbe.NONE);
    }

    /** Runs projected gradient with bounded cooperative cancellation. */
    public Result refit(Request request, CancellationProbe cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.checkpoint();
        if (request.mode() == Mode.OFF) {
            return skipped(Status.SKIPPED_OFF, request.initialPoints());
        }
        if (request.mode() == Mode.REDUCE_POINTS_ONLY) {
            return skipped(Status.SKIPPED_REDUCE_ONLY, request.initialPoints());
        }
        FrozenProblem problem = freeze(request, cancellation);
        List<MetricPoint> current = request.initialPoints();
        ObjectiveEvaluation initial = evaluate(problem, current, cancellation);
        if (!problem.movableEvidence) {
            return new Result(Status.SKIPPED_NO_ELIGIBLE_CONTROLS, current,
                    initial.objective(), initial.objective(), 0,
                    Double.POSITIVE_INFINITY, 0.0, false, List.of("no-free-eligible-controls"));
        }
        if (!initial.supported() || !validate(request, current).feasible()) {
            return new Result(Status.REVERTED, current, initial.objective(), initial.objective(), 0,
                    Double.POSITIVE_INFINITY, 0.0, false, List.of("initial-geometry-infeasible"));
        }
        double[] scalingX = new double[current.size()];
        double[] scalingY = new double[current.size()];
        double dimensionScale = NORMALIZATION_LENGTH_METERS;
        for (int index = 0; index < current.size(); index++) {
            scalingX[index] = 1.0 / Math.max(1.0,
                    Math.abs(initial.gradient().get(index).xMeters() * dimensionScale));
            scalingY[index] = 1.0 / Math.max(1.0,
                    Math.abs(initial.gradient().get(index).yMeters() * dimensionScale));
        }

        ObjectiveEvaluation evaluation = initial;
        int stableIterations = 0;
        double maximumMovement = 0.0;
        double residual = scaledResidual(evaluation.gradient(), scalingX, scalingY, dimensionScale);
        List<String> rejectedCodes = new ArrayList<>();
        boolean acceptedAny = false;
        for (int iteration = 0; iteration < request.config().maximumIterations(); iteration++) {
            cancellation.checkpoint();
            if (residual < request.config().gradientTolerance()) {
                return retainedResult(Status.CONVERGED, problem, current, initial.objective(),
                        evaluation.objective(), iteration, residual, acceptedAny, rejectedCodes, cancellation);
            }
            List<MetricPoint> direction = direction(evaluation.gradient(), scalingX, scalingY, dimensionScale,
                    request.fixedIndices());
            boolean accepted = false;
            List<MetricPoint> previousPoints = current;
            double previousObjective = evaluation.objective();
            double trialStep = 1.0;
            List<MetricPoint> acceptedPoints = current;
            ObjectiveEvaluation acceptedEvaluation = evaluation;
            for (int halving = 0; halving < request.config().maximumLineSearchHalvings(); halving++) {
                cancellation.checkpoint();
                List<MetricPoint> trial = boundedTrial(current, request.trustOrigin(), direction, trialStep,
                        request.config().trustRadiusMeters(), request.fixedIndices());
                Validation validation = validate(request, trial);
                if (!validation.feasible()) {
                    rejectedCodes.add(validation.code());
                    trialStep *= 0.5;
                    continue;
                }
                ObjectiveEvaluation trialEvaluation = evaluate(problem, trial, cancellation);
                double slope = dot(evaluation.gradient(), displacement(current, trial));
                if (!trialEvaluation.supported() || !(slope < 0.0)
                        || trialEvaluation.objective() > evaluation.objective()
                                + request.config().armijoCoefficient() * slope) {
                    trialStep *= 0.5;
                    continue;
                }
                accepted = true;
                acceptedPoints = trial;
                acceptedEvaluation = trialEvaluation;
                break;
            }
            if (!accepted) {
                Status status = acceptedAny && evaluation.objective() < initial.objective()
                        ? Status.LINE_SEARCH_FAILED_RETAINED : Status.REVERTED;
                List<MetricPoint> points = status == Status.REVERTED ? request.initialPoints() : current;
                double objective = status == Status.REVERTED ? initial.objective() : evaluation.objective();
                return retainedResult(status, problem, points, initial.objective(), objective, iteration,
                        residual, status != Status.REVERTED, rejectedCodes, cancellation);
            }
            current = acceptedPoints;
            evaluation = acceptedEvaluation;
            acceptedAny = true;
            maximumMovement = maxDistance(current, request.trustOrigin());
            double relativeImprovement = (previousObjective - evaluation.objective())
                    / Math.max(1.0, Math.abs(previousObjective));
            double stepMovement = maxDistance(current, previousPoints);
            if (relativeImprovement < request.config().objectiveTolerance()
                    && stepMovement < request.config().movementToleranceMeters()) {
                stableIterations++;
            } else {
                stableIterations = 0;
            }
            residual = scaledResidual(evaluation.gradient(), scalingX, scalingY, dimensionScale);
            if (stableIterations >= request.config().stableIterationsRequired()) {
                return retainedResult(Status.CONVERGED, problem, current, initial.objective(),
                        evaluation.objective(), iteration + 1, residual, true, rejectedCodes, cancellation);
            }
        }
        return retainedResult(Status.ITERATION_LIMIT_RETAINED, problem, current, initial.objective(),
                evaluation.objective(), request.config().maximumIterations(), residual,
                acceptedAny && evaluation.objective() < initial.objective(), rejectedCodes, cancellation);
    }

    /** Independently re-extracts image branches on final geometry without altering the frozen objective. */
    boolean finalBranchSupported(FrozenProblem problem, List<MetricPoint> points,
            CancellationProbe cancellation) {
        if (problem == null || points == null || cancellation == null
                || points.size() != problem.request.initialPoints().size()) {
            throw new IllegalArgumentException("Final branch validation inputs are inconsistent");
        }
        List<MetricPoint> meshPoints = problem.mesh.interpolate(points);
        for (int index = 0; index < meshPoints.size(); index++) {
            cancellation.checkpoint();
            FrozenProfile original = problem.profiles.get(index);
            if (original.support() != FrozenSupport.MEASURED) continue;
            FrozenProfile measured = problem.request.image().freezeProfile(meshPoints.get(index),
                    tangent(meshPoints, index), cancellation);
            if (measured.support() != FrozenSupport.MEASURED) return false;
            MetricPoint originalCenter = profileCenter(original);
            MetricPoint measuredCenter = profileCenter(measured);
            double branchTolerance = Math.max(problem.request.config().sourcePitchMeters(),
                    original.localizationSigmaMeters() + measured.localizationSigmaMeters());
            if (originalCenter.distanceTo(measuredCenter) > branchTolerance + 1.0e-9) return false;
            if (original.orientationCertainty() > 0.0 && measured.orientationCertainty() > 0.0
                    && undirectedDistance(original.orientationRadians(), measured.orientationRadians())
                            > Math.toRadians(30.0)) {
                return false;
            }
        }
        return true;
    }

    private Result retainedResult(Status status, FrozenProblem problem, List<MetricPoint> points,
            double initialObjective, double finalObjective, int iterations, double residual,
            boolean accepted, List<String> codes, CancellationProbe cancellation) {
        Request request = problem.request;
        if (accepted && !finalBranchSupported(problem, points, cancellation)) {
            ArrayList<String> rejected = new ArrayList<>(codes);
            rejected.add("final-image-branch-unavailable");
            return new Result(Status.REVERTED, request.initialPoints(), initialObjective, initialObjective,
                    iterations, residual, maxDistance(request.initialPoints(), request.trustOrigin()),
                    false, deduplicate(rejected));
        }
        return new Result(status, points, initialObjective, finalObjective, iterations, residual,
                maxDistance(points, request.trustOrigin()), accepted, deduplicate(codes));
    }

    static long estimatedFrozenProfilePeakBytes(int meshPointCount, int profileSampleCount) {
        if (meshPointCount < 0 || profileSampleCount < 2) {
            throw new IllegalArgumentException("Frozen-profile dimensions are invalid");
        }
        try {
            long retainedProfiles = Math.multiplyExact((long) meshPointCount,
                    Math.addExact(160L, Math.multiplyExact(16L, profileSampleCount)));
            long activeProfile = Math.addExact(4_096L, Math.multiplyExact(96L, profileSampleCount));
            long meshAndGradients = Math.multiplyExact(384L, meshPointCount);
            return Math.addExact(Math.addExact(retainedProfiles, activeProfile), meshAndGradients);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void preflightFrozenProfiles(int meshPointCount, double sourcePitchMeters) {
        EvidenceModelParameters.Localization parameters = EvidenceModelParameters.defaults().localization();
        double halfWidth = Math.max(parameters.routeProfileHalfWidthMeters(), 4.0 * sourcePitchMeters);
        double requestedIntervals = Math.max(4.0,
                Math.ceil(2.0 * halfWidth / (sourcePitchMeters * 0.25)));
        if (!Double.isFinite(requestedIntervals) || requestedIntervals > Integer.MAX_VALUE - 1.0
                || estimatedFrozenProfilePeakBytes(meshPointCount, (int) requestedIntervals + 1)
                        > MAXIMUM_WORKING_BYTES) {
            throw new IllegalArgumentException("Frozen refit exceeds the deterministic resource bound");
        }
    }

    private static Result skipped(Status status, List<MetricPoint> points) {
        return new Result(status, points, Double.NaN, Double.NaN, 0, Double.NaN, 0.0, false, List.of());
    }

    private static ObjectiveEvaluation unsupported(int size) {
        List<MetricPoint> gradient = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            gradient.add(new MetricPoint(0, 0));
        }
        return new ObjectiveEvaluation(Double.POSITIVE_INFINITY, gradient, false);
    }

    private static Validation validate(Request request, List<MetricPoint> points) {
        for (int index = 0; index < points.size(); index++) {
            if (!request.branchCorridor().contains(points.get(index))
                    || points.get(index).distanceTo(request.trustOrigin().get(index))
                            > request.config().trustRadiusMeters() + 1.0e-9) {
                return Validation.rejected("outside-branch-or-trust-region");
            }
            if (index > 0 && !request.branchCorridor().containsSegment(points.get(index - 1), points.get(index))) {
                return Validation.rejected("segment-outside-branch-corridor");
            }
        }
        if (hasSelfIntersection(points)) {
            return Validation.rejected("self-intersection");
        }
        if (hasFoldback(points)) {
            return Validation.rejected("adjacent-foldback");
        }
        for (GeometryValidator validator : request.validators()) {
            Validation validation = validator.validate(points);
            if (!validation.feasible()) {
                return validation;
            }
        }
        return Validation.accepted();
    }

    private static List<MetricPoint> boundedTrial(List<MetricPoint> current, List<MetricPoint> origin,
            List<MetricPoint> direction, double step, double radius, Set<Integer> fixed) {
        List<MetricPoint> result = new ArrayList<>(current.size());
        for (int index = 0; index < current.size(); index++) {
            if (fixed.contains(index)) {
                result.add(current.get(index));
                continue;
            }
            MetricPoint proposed = add(current.get(index), scale(direction.get(index), step));
            MetricPoint radial = subtract(proposed, origin.get(index));
            double distance = Math.hypot(radial.xMeters(), radial.yMeters());
            if (distance > radius) {
                proposed = add(origin.get(index), scale(radial, radius / distance));
            }
            result.add(proposed);
        }
        return List.copyOf(result);
    }

    private static List<MetricPoint> direction(List<MetricPoint> gradient, double[] scalingX,
            double[] scalingY, double dimensionScale, Set<Integer> fixed) {
        List<MetricPoint> result = new ArrayList<>(gradient.size());
        for (int index = 0; index < gradient.size(); index++) {
            if (fixed.contains(index)) {
                result.add(new MetricPoint(0, 0));
            } else {
                result.add(new MetricPoint(-dimensionScale * scalingX[index]
                        * gradient.get(index).xMeters() * dimensionScale,
                        -dimensionScale * scalingY[index] * gradient.get(index).yMeters() * dimensionScale));
            }
        }
        return result;
    }

    private static double scaledResidual(List<MetricPoint> gradient, double[] scalingX,
            double[] scalingY, double dimensionScale) {
        double maximum = 0.0;
        for (int index = 0; index < gradient.size(); index++) {
            maximum = Math.max(maximum, Math.abs(scalingX[index] * gradient.get(index).xMeters() * dimensionScale));
            maximum = Math.max(maximum, Math.abs(scalingY[index] * gradient.get(index).yMeters() * dimensionScale));
        }
        return maximum;
    }

    private static double dot(List<MetricPoint> a, List<MetricPoint> b) {
        double result = 0.0;
        for (int index = 0; index < a.size(); index++) {
            result += a.get(index).xMeters() * b.get(index).xMeters()
                    + a.get(index).yMeters() * b.get(index).yMeters();
        }
        return result;
    }

    private static List<MetricPoint> displacement(List<MetricPoint> from, List<MetricPoint> to) {
        List<MetricPoint> result = new ArrayList<>(from.size());
        for (int index = 0; index < from.size(); index++) {
            result.add(subtract(to.get(index), from.get(index)));
        }
        return result;
    }

    private static List<CurvatureBlock> curvatureBlocks(SupportedCurvatureBank bank,
            Config config, double referenceLength) {
        ArrayList<CurvatureBlock> result = new ArrayList<>();
        List<SupportedCurvatureBank.Target> targets = bank.targets();
        int index = 0;
        while (index < targets.size()) {
            while (index < targets.size()
                    && targets.get(index).status() == SupportedCurvatureBank.Status.UNKNOWN) index++;
            if (index >= targets.size()) break;
            int first = index;
            ArrayList<InverseMetersVector> values = new ArrayList<>();
            while (index < targets.size()
                    && targets.get(index).status() != SupportedCurvatureBank.Status.UNKNOWN) {
                values.add(targets.get(index).curvature());
                index++;
            }
            result.add(new CurvatureBlock(first, values.size() + 2,
                    new GeometricCurvatureOperator(config.sourcePitchMeters(), referenceLength,
                            config.lambdaCurvature(), values)));
        }
        return List.copyOf(result);
    }

    private static void freezeRow(FrozenRefitMesh.Row row, FreezeReason reason,
            Set<Integer> frozen, Map<Integer, Set<FreezeReason>> freezeReasons) {
        addFreezeReason(row.leftControl(), reason, frozen, freezeReasons);
        addFreezeReason(row.rightControl(), reason, frozen, freezeReasons);
    }

    private static void addFreezeReason(int control, FreezeReason reason, Set<Integer> frozen,
            Map<Integer, Set<FreezeReason>> freezeReasons) {
        frozen.add(control);
        freezeReasons.computeIfAbsent(control, ignored -> new HashSet<>()).add(reason);
    }

    private static MetricPoint tangent(List<MetricPoint> points, int index) {
        MetricPoint first = points.get(index == 0 ? 0 : index - 1);
        MetricPoint second = points.get(index == points.size() - 1 ? points.size() - 1 : index + 1);
        MetricPoint tangent = subtract(second, first);
        if (!(Math.hypot(tangent.xMeters(), tangent.yMeters()) > 0.0)) {
            throw new IllegalArgumentException("Refit mesh tangent is degenerate");
        }
        return tangent;
    }

    private static double averageUndirected(double first, double second) {
        double difference = second - first;
        while (difference > Math.PI / 2.0) difference -= Math.PI;
        while (difference < -Math.PI / 2.0) difference += Math.PI;
        double result = first + 0.5 * difference;
        result %= Math.PI;
        return result < 0.0 ? result + Math.PI : result;
    }

    private static double undirectedDistance(double first, double second) {
        double difference = Math.abs(first - second);
        difference %= Math.PI;
        return Math.min(difference, Math.PI - difference);
    }

    private static MetricPoint profileCenter(FrozenProfile profile) {
        double offset = 0.5 * (profile.coreMinimumMeters() + profile.coreMaximumMeters());
        return new MetricPoint(profile.origin().xMeters() + profile.normal().xMeters() * offset,
                profile.origin().yMeters() + profile.normal().yMeters() * offset);
    }

    private static double polylineLength(List<MetricPoint> points) {
        double length = 0.0;
        for (int index = 1; index < points.size(); index++) {
            length += points.get(index - 1).distanceTo(points.get(index));
        }
        return length;
    }

    private static double maxDistance(List<MetricPoint> a, List<MetricPoint> b) {
        double maximum = 0.0;
        for (int index = 0; index < a.size(); index++) {
            maximum = Math.max(maximum, a.get(index).distanceTo(b.get(index)));
        }
        return maximum;
    }

    private static List<String> deduplicate(List<String> values) {
        return List.copyOf(new java.util.LinkedHashSet<>(values));
    }

    private static boolean hasSelfIntersection(List<MetricPoint> points) {
        for (int first = 0; first < points.size() - 1; first++) {
            for (int second = first + 2; second < points.size() - 1; second++) {
                if (first == 0 && second == points.size() - 2
                        && points.get(0).equals(points.get(points.size() - 1))) {
                    continue;
                }
                if (properIntersection(points.get(first), points.get(first + 1),
                        points.get(second), points.get(second + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasFoldback(List<MetricPoint> points) {
        for (int index = 1; index < points.size() - 1; index++) {
            MetricPoint incoming = subtract(points.get(index), points.get(index - 1));
            MetricPoint outgoing = subtract(points.get(index + 1), points.get(index));
            double product = Math.hypot(incoming.xMeters(), incoming.yMeters())
                    * Math.hypot(outgoing.xMeters(), outgoing.yMeters());
            if (product > 0.0 && incoming.xMeters() * outgoing.xMeters()
                    + incoming.yMeters() * outgoing.yMeters() < -0.25 * product) {
                return true;
            }
        }
        return false;
    }

    private static boolean properIntersection(MetricPoint a, MetricPoint b, MetricPoint c, MetricPoint d) {
        double o1 = orientation(a, b, c);
        double o2 = orientation(a, b, d);
        double o3 = orientation(c, d, a);
        double o4 = orientation(c, d, b);
        return o1 * o2 < -1.0e-12 && o3 * o4 < -1.0e-12;
    }

    private static double orientation(MetricPoint a, MetricPoint b, MetricPoint c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private static MetricPoint interpolate(MetricPoint a, MetricPoint b, double fraction) {
        return add(a, scale(subtract(b, a), fraction));
    }

    private static MetricPoint add(MetricPoint a, MetricPoint b) {
        return new MetricPoint(a.xMeters() + b.xMeters(), a.yMeters() + b.yMeters());
    }

    private static MetricPoint subtract(MetricPoint a, MetricPoint b) {
        return new MetricPoint(a.xMeters() - b.xMeters(), a.yMeters() - b.yMeters());
    }

    private static MetricPoint scale(MetricPoint point, double factor) {
        return new MetricPoint(point.xMeters() * factor, point.yMeters() * factor);
    }
}
