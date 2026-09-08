package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;

/** Deterministic projected-gradient refitter over one frozen scalar-image objective. */
public final class ImageSupportedRefitter {
    /** Controls whether coordinate refinement is permitted. */
    public enum Mode { OFF, REDUCE_POINTS_ONLY, IMAGE_SUPPORTED }

    /** Truthful numerical outcome; budget exhaustion and failed line search are not convergence. */
    public enum Status {
        SKIPPED_OFF,
        SKIPPED_REDUCE_ONLY,
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
            return new Config(sourcePitchMeters, 3.0, 0.12, 1.25 * sourcePitchMeters,
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
            if (initialPoints.size() < 2 || initialPoints.size() != trustOrigin.size()
                    || invalidFixedIndex) {
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

    /** Objective value and analytic per-occurrence gradient in metric coordinates. */
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
        private final double referenceLength;
        private final List<MetricPoint> curvatureTargets;
        private final List<MetricPoint> segmentNormals;
        private final List<Integer> segmentSampleCounts;

        private FrozenProblem(Request request, double referenceLength, List<MetricPoint> curvatureTargets,
                List<MetricPoint> segmentNormals, List<Integer> segmentSampleCounts) {
            this.request = request;
            this.referenceLength = referenceLength;
            this.curvatureTargets = List.copyOf(curvatureTargets);
            this.segmentNormals = List.copyOf(segmentNormals);
            this.segmentSampleCounts = List.copyOf(segmentSampleCounts);
        }
    }

    /** Freezes image descriptors, branch identity, arclength and supported-curvature targets. */
    public FrozenProblem freeze(Request request) {
        double length = polylineLength(request.initialPoints());
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Refit input must have positive physical length");
        }
        List<MetricPoint> targets = new ArrayList<>(request.initialPoints().size());
        targets.add(new MetricPoint(0, 0));
        for (int index = 1; index < request.initialPoints().size() - 1; index++) {
            MetricPoint previous = request.initialPoints().get(index - 1);
            MetricPoint current = request.initialPoints().get(index);
            MetricPoint next = request.initialPoints().get(index + 1);
            MetricPoint secondDifference = new MetricPoint(previous.xMeters() - 2.0 * current.xMeters() + next.xMeters(),
                    previous.yMeters() - 2.0 * current.yMeters() + next.yMeters());
            boolean coherentSupport = coherentBendSupport(request.initialPoints(), index, request.image());
            targets.add(coherentSupport ? secondDifference : new MetricPoint(0, 0));
        }
        targets.add(new MetricPoint(0, 0));

        List<MetricPoint> normals = new ArrayList<>(request.initialPoints().size() - 1);
        List<Integer> sampleCounts = new ArrayList<>(request.initialPoints().size() - 1);
        double spacing = Math.min(2.0, Math.max(request.config().sourcePitchMeters() / 2.0, 0.5));
        for (int index = 1; index < request.initialPoints().size(); index++) {
            MetricPoint delta = subtract(request.initialPoints().get(index), request.initialPoints().get(index - 1));
            double norm = Math.max(1.0e-12, Math.hypot(delta.xMeters(), delta.yMeters()));
            normals.add(new MetricPoint(-delta.yMeters() / norm, delta.xMeters() / norm));
            sampleCounts.add(Math.max(1, (int) Math.ceil(norm / spacing)));
        }
        return new FrozenProblem(request, length, targets, normals, sampleCounts);
    }

    /** Evaluates the frozen arclength-weighted image, curvature and trust objective. */
    public ObjectiveEvaluation evaluate(FrozenProblem problem, List<MetricPoint> points) {
        if (points.size() != problem.request.initialPoints().size()) {
            throw new IllegalArgumentException("Objective geometry occurrence count changed");
        }
        int size = points.size();
        double[] gx = new double[size];
        double[] gy = new double[size];
        double objective = 0.0;
        for (int segment = 0; segment < size - 1; segment++) {
            MetricPoint start = points.get(segment);
            MetricPoint end = points.get(segment + 1);
            MetricPoint delta = subtract(end, start);
            double length = Math.hypot(delta.xMeters(), delta.yMeters());
            if (!(length > 1.0e-9)) {
                return unsupported(size);
            }
            int samples = problem.segmentSampleCounts.get(segment);
            double meanCost = 0.0;
            double derivativeStartX = 0.0;
            double derivativeStartY = 0.0;
            double derivativeEndX = 0.0;
            double derivativeEndY = 0.0;
            for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
                double fraction = (sampleIndex + 0.5) / samples;
                MetricPoint location = interpolate(start, end, fraction);
                java.util.Optional<ImageCostField.Sample> sample = problem.request.image().sample(location);
                if (sample.isEmpty()) {
                    return unsupported(size);
                }
                ImageCostField.Sample value = sample.orElseThrow();
                meanCost += value.centerCost() / samples;
                derivativeStartX += (1.0 - fraction) * value.centerGradientX() / samples;
                derivativeStartY += (1.0 - fraction) * value.centerGradientY() / samples;
                derivativeEndX += fraction * value.centerGradientX() / samples;
                derivativeEndY += fraction * value.centerGradientY() / samples;
            }
            MetricPoint normal = problem.segmentNormals.get(segment);
            double dot = delta.xMeters() * normal.xMeters() + delta.yMeters() * normal.yMeters();
            double lengthSquared = length * length;
            double orientation = dot * dot / lengthSquared;
            double segmentDensity = meanCost + 0.5 * orientation;
            objective += length * segmentDensity / problem.referenceLength;

            double lengthDerivativeX = delta.xMeters() / length;
            double lengthDerivativeY = delta.yMeters() / length;
            double orientationDerivativeX = 2.0 * dot * normal.xMeters() / lengthSquared
                    - 2.0 * dot * dot * delta.xMeters() / (lengthSquared * lengthSquared);
            double orientationDerivativeY = 2.0 * dot * normal.yMeters() / lengthSquared
                    - 2.0 * dot * dot * delta.yMeters() / (lengthSquared * lengthSquared);
            double commonX = (lengthDerivativeX * segmentDensity + length * 0.5 * orientationDerivativeX)
                    / problem.referenceLength;
            double commonY = (lengthDerivativeY * segmentDensity + length * 0.5 * orientationDerivativeY)
                    / problem.referenceLength;
            gx[segment] += -commonX + length * derivativeStartX / problem.referenceLength;
            gy[segment] += -commonY + length * derivativeStartY / problem.referenceLength;
            gx[segment + 1] += commonX + length * derivativeEndX / problem.referenceLength;
            gy[segment + 1] += commonY + length * derivativeEndY / problem.referenceLength;
        }

        double pitchSquared = problem.request.config().sourcePitchMeters()
                * problem.request.config().sourcePitchMeters();
        for (int index = 1; index < size - 1; index++) {
            MetricPoint target = problem.curvatureTargets.get(index);
            double rx = points.get(index - 1).xMeters() - 2.0 * points.get(index).xMeters()
                    + points.get(index + 1).xMeters() - target.xMeters();
            double ry = points.get(index - 1).yMeters() - 2.0 * points.get(index).yMeters()
                    + points.get(index + 1).yMeters() - target.yMeters();
            double coefficient = problem.request.config().lambdaCurvature()
                    / problem.referenceLength / pitchSquared;
            double weight = coefficient * localWeight(points, index);
            objective += 0.5 * weight * (rx * rx + ry * ry);
            gx[index - 1] += weight * rx;
            gy[index - 1] += weight * ry;
            gx[index] -= 2.0 * weight * rx;
            gy[index] -= 2.0 * weight * ry;
            gx[index + 1] += weight * rx;
            gy[index + 1] += weight * ry;

            MetricPoint left = subtract(points.get(index), points.get(index - 1));
            MetricPoint right = subtract(points.get(index + 1), points.get(index));
            double leftLength = Math.hypot(left.xMeters(), left.yMeters());
            double rightLength = Math.hypot(right.xMeters(), right.yMeters());
            double weightDerivativeFactor = 0.25 * coefficient * (rx * rx + ry * ry);
            gx[index - 1] -= weightDerivativeFactor * left.xMeters() / leftLength;
            gy[index - 1] -= weightDerivativeFactor * left.yMeters() / leftLength;
            gx[index] += weightDerivativeFactor * (left.xMeters() / leftLength
                    - right.xMeters() / rightLength);
            gy[index] += weightDerivativeFactor * (left.yMeters() / leftLength
                    - right.yMeters() / rightLength);
            gx[index + 1] += weightDerivativeFactor * right.xMeters() / rightLength;
            gy[index + 1] += weightDerivativeFactor * right.yMeters() / rightLength;
        }

        for (int index = 0; index < size; index++) {
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
            gradient.add(problem.request.fixedIndices().contains(index)
                    ? new MetricPoint(0, 0) : new MetricPoint(gx[index], gy[index]));
        }
        return new ObjectiveEvaluation(objective, gradient, true);
    }

    /** Runs projected gradient with fixed diagonal scaling and Armijo backtracking. */
    public Result refit(Request request) {
        if (request.mode() == Mode.OFF) {
            return skipped(Status.SKIPPED_OFF, request.initialPoints());
        }
        if (request.mode() == Mode.REDUCE_POINTS_ONLY) {
            return skipped(Status.SKIPPED_REDUCE_ONLY, request.initialPoints());
        }
        FrozenProblem problem = freeze(request);
        List<MetricPoint> current = request.initialPoints();
        ObjectiveEvaluation initial = evaluate(problem, current);
        if (!initial.supported() || !validate(request, current).feasible()) {
            return new Result(Status.REVERTED, current, initial.objective(), initial.objective(), 0,
                    Double.POSITIVE_INFINITY, 0.0, false, List.of("initial-geometry-infeasible"));
        }
        double[] scalingX = new double[current.size()];
        double[] scalingY = new double[current.size()];
        double dimensionScale = problem.referenceLength;
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
            if (residual < request.config().gradientTolerance()) {
                return result(Status.CONVERGED, request, current, initial.objective(), evaluation.objective(),
                        iteration, residual, acceptedAny, rejectedCodes);
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
                List<MetricPoint> trial = boundedTrial(current, request.trustOrigin(), direction, trialStep,
                        request.config().trustRadiusMeters(), request.fixedIndices());
                Validation validation = validate(request, trial);
                if (!validation.feasible()) {
                    rejectedCodes.add(validation.code());
                    trialStep *= 0.5;
                    continue;
                }
                ObjectiveEvaluation trialEvaluation = evaluate(problem, trial);
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
                return result(status, request, points, initial.objective(), objective, iteration,
                        residual, status != Status.REVERTED, rejectedCodes);
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
                return new Result(Status.CONVERGED, current, initial.objective(), evaluation.objective(),
                        iteration + 1, residual, maximumMovement, true, deduplicate(rejectedCodes));
            }
        }
        return new Result(Status.ITERATION_LIMIT_RETAINED, current, initial.objective(), evaluation.objective(),
                request.config().maximumIterations(), residual, maximumMovement,
                acceptedAny && evaluation.objective() < initial.objective(), deduplicate(rejectedCodes));
    }

    private static Result skipped(Status status, List<MetricPoint> points) {
        return new Result(status, points, Double.NaN, Double.NaN, 0, Double.NaN, 0.0, false, List.of());
    }

    private static Result result(Status status, Request request, List<MetricPoint> points,
            double initialObjective, double finalObjective, int iterations, double residual,
            boolean accepted, List<String> codes) {
        return new Result(status, points, initialObjective, finalObjective, iterations, residual,
                maxDistance(points, request.trustOrigin()), accepted, deduplicate(codes));
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

    private static double localWeight(List<MetricPoint> points, int index) {
        return 0.5 * (points.get(index - 1).distanceTo(points.get(index))
                + points.get(index).distanceTo(points.get(index + 1)));
    }

    private static boolean coherentBendSupport(List<MetricPoint> points, int apex, ImageCostField image) {
        double[] chainage = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            chainage[index] = chainage[index - 1] + points.get(index - 1).distanceTo(points.get(index));
        }
        int supportedWindows = 0;
        for (double window : new double[] {6.0, 10.0, 20.0}) {
            double halfWindow = window / 2.0;
            int left = apex - 1;
            while (left > 0 && chainage[apex] - chainage[left] < halfWindow) {
                left--;
            }
            int right = apex + 1;
            while (right < points.size() - 1 && chainage[right] - chainage[apex] < halfWindow) {
                right++;
            }
            if (left >= apex || right <= apex) {
                continue;
            }
            double routeCost = image.meanPolylineCost(points.subList(left, right + 1));
            double chordCost = image.meanSegmentCost(points.get(left), points.get(right));
            if (Double.isFinite(routeCost) && Double.isFinite(chordCost)
                    && routeCost + 0.02 < chordCost && image.supports(points.get(apex))) {
                supportedWindows++;
            }
        }
        return supportedWindows >= 2;
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
