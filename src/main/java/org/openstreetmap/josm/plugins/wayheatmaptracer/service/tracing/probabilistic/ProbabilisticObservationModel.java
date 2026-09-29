package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.OrderedDoubleSum;

/** Evaluates the deterministic finite observation mixture on an admitted lateral lattice. */
public final class ProbabilisticObservationModel {
    private static final double LOG_FLOOR = StrictMath.log(1e-300);

    /**
     * Converts scalar samples and explicit evidence components to normalized unary costs.
     *
     * @param profile complete scalar profile
     * @param lattice admitted physical quadrature cells
     * @param parameters versioned evidence parameters
     * @return profile ready for global longitudinal inference
     */
    public InferenceProfile evaluate(ProbabilisticProfile profile, ProbabilisticStateLattice lattice,
        EvidenceModelParameters parameters) {
        return evaluate(profile, lattice, parameters, true);
    }

    /** Evaluates with an explicit caller-owned orientation-reliability capability. */
    InferenceProfile evaluate(ProbabilisticProfile profile, ProbabilisticStateLattice lattice,
        EvidenceModelParameters parameters, boolean attenuateOrientation) {
        if (profile == null || lattice == null || parameters == null) {
            throw new IllegalArgumentException("Observation evaluation requires complete inputs");
        }
        List<ObservationComponent> components = lattice.components();
        int states = lattice.cells().size();
        ProfileStateEvidence evidence = ProfileStateEvidence.capture(profile, lattice.cells());
        double[][] logDensity = new double[components.size()][states];
        for (double[] row : logDensity) {
            java.util.Arrays.fill(row, Double.NEGATIVE_INFINITY);
        }

        for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
            ObservationComponent component = components.get(componentIndex);
            switch (component.kind()) {
                case MISSING -> fillUniform(logDensity[componentIndex], lattice.cells());
                case MEASURED -> fillMeasured(logDensity[componentIndex], profile, lattice.cells(), evidence,
                    findMode(profile, component), parameters);
                case CENSORED -> fillCensored(logDensity[componentIndex], profile, lattice.cells(),
                    findCensored(profile, component));
                default -> throw new IllegalStateException("Unhandled observation component");
            }
        }

        double[] priors = effectiveComponentPriors(profile, components);
        double[] unary = new double[states];
        double[][] responsibilities = new double[states][components.size()];
        for (int state = 0; state < states; state++) {
            double[] terms = new double[components.size()];
            for (int component = 0; component < components.size(); component++) {
                double prior = priors[component];
                terms[component] = prior == 0.0 ? Double.NEGATIVE_INFINITY
                    : StrictMath.log(prior) + logDensity[component][state];
            }
            double logMixture = logSumExp(terms);
            unary[state] = lattice.cells().get(state).exactAnchor() ? 0.0
                : -Math.max(LOG_FLOOR, logMixture);
            for (int component = 0; component < terms.length; component++) {
                responsibilities[state][component] = Double.isFinite(terms[component])
                    ? StrictMath.exp(terms[component] - logMixture) : 0.0;
            }
        }

        boolean localized = components.stream().anyMatch(component -> component.kind() == ObservationComponent.Kind.MEASURED
            && component.priorWeight() > 0.0);
        ObservationOwnership ownership = localized ? ObservationOwnership.DIRECT_TWO_SIDED
            : profile.censoredModes().isEmpty() ? validSampleExists(profile)
                ? ObservationOwnership.NO_SIGNAL_VALID_RASTER : ObservationOwnership.NO_RASTER
                : ObservationOwnership.CORE_CENSORED;
        return new InferenceProfile(profile.chainageMeters(), profile.anchor(), profile.normalUnit(),
            lattice.cells(), unary, orientationByBranch(profile),
            orientationReliabilityByBranch(profile, attenuateOrientation),
            ownership, !localized, responsibilities);
    }

    /** Applies frozen mode reliability after baseline mixture construction. */
    static double[] effectiveComponentPriors(ProbabilisticProfile profile,
            List<ObservationComponent> components) {
        double[] result = components.stream().mapToDouble(ObservationComponent::priorWeight).toArray();
        int uniform = -1;
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).kind() == ObservationComponent.Kind.MISSING) uniform = index;
        }
        if (uniform < 0) throw new IllegalArgumentException("Observation mixture has no uniform component");
        for (int index = 0; index < components.size(); index++) {
            ObservationComponent component = components.get(index);
            if (component.kind() != ObservationComponent.Kind.MEASURED) continue;
            ProbabilisticProfile.Mode mode = findMode(profile, component);
            double original = result[index];
            double retained = original * mode.positionalReliability();
            result[index] = retained;
            result[uniform] += original - retained;
        }
        return result;
    }

    private static Map<String, ImageOrientationSupport> orientationByBranch(
        ProbabilisticProfile profile) {
        Map<String, ImageOrientationSupport> result = new LinkedHashMap<>();
        ImageOrientationSupport profileSupport = profile.orientationSupport();
        profile.modes().forEach(mode -> {
            ImageOrientationSupport support = mode.orientationSupport();
            if (profileSupport.status() == ImageOrientationSupport.Status.LEGACY_POINT_DIRECTIONS
                && support.status() == ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT) {
                support = profileSupport;
            }
            result.put(mode.id(), support);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, Double> orientationReliabilityByBranch(
        ProbabilisticProfile profile, boolean attenuateOrientation) {
        Map<String, Double> result = new LinkedHashMap<>();
        profile.modes().forEach(mode -> result.put(mode.id(),
                attenuateOrientation ? mode.positionalReliability() : 1.0));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void fillMeasured(double[] output, ProbabilisticProfile profile,
        List<LateralStateCell> cells, ProfileStateEvidence evidence, ProbabilisticProfile.Mode mode,
        EvidenceModelParameters parameters) {
        if (evidence.peak() <= profile.noiseFloor()) {
            return;
        }
        for (int state = 0; state < cells.size(); state++) {
            double offset = cells.get(state).offsetMeters();
            double intensity = evidence.intensityAt(state);
            if (!Double.isFinite(intensity)) {
                continue;
            }
            double response = clamp((intensity - profile.noiseFloor())
                / Math.max(evidence.peak() - profile.noiseFloor(), 1e-12), 0.0, 1.0);
            double presenceCost = -StrictMath.log(Math.max(1e-6, response));
            double distance = distanceToInterval(offset, mode.coreMinimumMeters(), mode.coreMaximumMeters());
            double centerScale = Math.max(profile.sourcePitchMeters() * 0.5, mode.localizationSigmaMeters());
            double centerCost = EvidenceModelParameters.huber(distance / centerScale);
            output[state] = -presenceCost - parameters.centerWeight() * centerCost;
        }
        normalizeDensity(output, cells);
    }

    /** Exact per-profile/state scalar terms shared by measured mixture components. */
    private static final class ProfileStateEvidence {
        private final double peak;
        private final double[] intensities;

        private ProfileStateEvidence(double peak, double[] intensities) {
            this.peak = peak;
            this.intensities = intensities;
        }

        static ProfileStateEvidence capture(ProbabilisticProfile profile,
            List<LateralStateCell> cells) {
            double peak = profile.samples().stream().filter(ProbabilisticProfile.Sample::valid)
                .mapToDouble(ProbabilisticProfile.Sample::intensity).max().orElse(profile.noiseFloor());
            double[] intensities = new double[cells.size()];
            for (int state = 0; state < cells.size(); state++) {
                intensities[state] = interpolate(profile.samples(), cells.get(state).offsetMeters());
            }
            return new ProfileStateEvidence(peak, intensities);
        }

        double peak() {
            return peak;
        }

        double intensityAt(int state) {
            return intensities[state];
        }
    }

    private static void fillCensored(double[] output, ProbabilisticProfile profile,
        List<LateralStateCell> cells, ProbabilisticProfile.CensoredMode mode) {
        for (int state = 0; state < cells.size(); state++) {
            double potential = 0.0;
            if (mode.gradientSupported()) {
                double sign = mode.side() == ProbabilisticProfile.CensorSide.RIGHT ? 1.0 : -1.0;
                potential = EvidenceModelParameters.huber(Math.max(0.0,
                    -sign * (cells.get(state).offsetMeters() - mode.observedBoundaryMeters()))
                    / profile.sourcePitchMeters());
            }
            output[state] = -potential;
        }
        normalizeDensity(output, cells);
    }

    private static void fillUniform(double[] output, List<LateralStateCell> cells) {
        OrderedDoubleSum reduction = new OrderedDoubleSum();
        for (LateralStateCell cell : cells) reduction.add(cell.quadratureWidthMeters());
        double width = reduction.value();
        java.util.Arrays.fill(output, -StrictMath.log(width));
    }

    private static void normalizeDensity(double[] logValues, List<LateralStateCell> cells) {
        double maximum = java.util.Arrays.stream(logValues).max().orElse(Double.NEGATIVE_INFINITY);
        if (!Double.isFinite(maximum)) {
            return;
        }
        double sum = 0.0;
        for (int state = 0; state < logValues.length; state++) {
            if (Double.isFinite(logValues[state])) {
                sum += StrictMath.exp(logValues[state] - maximum) * cells.get(state).quadratureWidthMeters();
            }
        }
        double logNormalizer = maximum + StrictMath.log(sum);
        for (int state = 0; state < logValues.length; state++) {
            if (Double.isFinite(logValues[state])) {
                logValues[state] -= logNormalizer;
            }
        }
    }

    private static double interpolate(List<ProbabilisticProfile.Sample> samples, double offset) {
        int exact = java.util.Collections.binarySearch(samples,
            new ProbabilisticProfile.Sample(offset, Double.NaN, false),
            Comparator.comparingDouble(ProbabilisticProfile.Sample::offsetMeters));
        if (exact >= 0) {
            ProbabilisticProfile.Sample sample = samples.get(exact);
            return sample.valid() ? sample.intensity() : Double.NaN;
        }
        int insertion = -exact - 1;
        if (insertion == 0 || insertion == samples.size()) {
            return Double.NaN;
        }
        ProbabilisticProfile.Sample left = samples.get(insertion - 1);
        ProbabilisticProfile.Sample right = samples.get(insertion);
        if (!left.valid() || !right.valid()) {
            return Double.NaN;
        }
        double fraction = (offset - left.offsetMeters()) / (right.offsetMeters() - left.offsetMeters());
        return left.intensity() + fraction * (right.intensity() - left.intensity());
    }

    private static ProbabilisticProfile.Mode findMode(ProbabilisticProfile profile,
        ObservationComponent component) {
        return profile.modes().stream().filter(mode -> mode.evidenceLineage().equals(component.evidenceLineage()))
            .min(Comparator.comparing(ProbabilisticProfile.Mode::id)).orElseThrow();
    }

    private static ProbabilisticProfile.CensoredMode findCensored(ProbabilisticProfile profile,
        ObservationComponent component) {
        return profile.censoredModes().stream()
            .filter(mode -> mode.evidenceLineage().equals(component.evidenceLineage()))
            .min(Comparator.comparing(ProbabilisticProfile.CensoredMode::id)).orElseThrow();
    }

    private static boolean validSampleExists(ProbabilisticProfile profile) {
        return profile.samples().stream().anyMatch(ProbabilisticProfile.Sample::valid);
    }

    private static double distanceToInterval(double value, double minimum, double maximum) {
        return value < minimum ? minimum - value : value > maximum ? value - maximum : 0.0;
    }

    private static double logSumExp(double[] values) {
        double maximum = java.util.Arrays.stream(values).max().orElse(Double.NEGATIVE_INFINITY);
        if (!Double.isFinite(maximum)) {
            return Double.NEGATIVE_INFINITY;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += StrictMath.exp(value - maximum);
        }
        return maximum + StrictMath.log(sum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
