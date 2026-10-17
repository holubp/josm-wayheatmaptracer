package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.function.Supplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Immutable candidate observation-mixture rows and their one shared reducer. */
public final class BObservationMixture {
    public static final double REFERENCE_LENGTH_METERS = 1.0;
    private static final double LOG_REFERENCE_LENGTH = StrictMath.log(REFERENCE_LENGTH_METERS);
    private static final double LOG_LIKELIHOOD_FLOOR = StrictMath.log(1e-300);
    private static final double NORMALIZATION_TOLERANCE = 1e-10;
    private static final long MAX_REDUCTION_WORK = 8_000_000L;

    /** Typed provenance for the source row represented by one mixture component. */
    public enum Outcome {
        BASELINE_MISSING,
        BASELINE_CENSORED,
        MEASURED,
        OBSERVED_ZERO_EXCESS,
        NO_ADMITTED_TENT_SUPPORT,
        UNAVAILABLE_ORIGINAL_RECEIPT,
        UNAVAILABLE_SOURCE_PITCH,
        GROUPED_PARENT_UNAVAILABLE
    }

    /** One normalized candidate component row on the complete admitted cell lattice. */
    public static final class Component {
        private final String id;
        private final String lineage;
        private final ObservationComponent.Kind kind;
        private final Outcome outcome;
        private final double mass;
        private final double[] logL0Density;

        private Component(String id, String lineage, ObservationComponent.Kind kind, Outcome outcome,
                double mass, double[] logL0Density) {
            this.id = id;
            this.lineage = lineage;
            this.kind = kind;
            this.outcome = outcome;
            this.mass = mass;
            this.logL0Density = logL0Density;
        }

        /** Copies one component row under the supplied attempt owner. */
        public static Component create(String id, String lineage, ObservationComponent.Kind kind,
                double mass, double[] logL0Density, AttemptMemoryLedger.Owner owner) {
            return create(id, lineage, kind, mass, logL0Density,
                    defaultOutcome(kind, mass, logL0Density), owner);
        }

        /** Copies one component row with explicit source-outcome provenance. */
        public static Component create(String id, String lineage, ObservationComponent.Kind kind,
                double mass, double[] logL0Density, Outcome outcome, AttemptMemoryLedger.Owner owner) {
            if (id == null || id.isBlank() || lineage == null || lineage.isBlank() || kind == null
                    || outcome == null || !Double.isFinite(mass) || mass < 0.0 || mass > 1.0
                    || logL0Density == null || logL0Density.length == 0) {
                throw new IllegalArgumentException("Mixture component is incomplete");
            }
            for (double value : logL0Density) {
                if (Double.isNaN(value) || value == Double.POSITIVE_INFINITY) {
                    throw new IllegalArgumentException("Component log density must be finite or negative infinity");
                }
            }
            if (mass > 0.0 && allNegativeInfinity(logL0Density)) {
                throw new IllegalArgumentException("Positive mass cannot have zero density support");
            }
            validateOutcome(kind, outcome, mass, logL0Density);
            AttemptMemoryLedger.Owner transaction = transaction(owner, "mixture-component");
            try {
                double[] copy = chargedArrayCopy(logL0Density, transaction);
                Component component = chargedObject(
                        AttemptMemoryLedger.objectBytes(6L * Long.BYTES),
                        () -> new Component(id, lineage, kind, outcome, mass, copy), transaction);
                commit(transaction, owner);
                return component;
            } finally {
                closeTransaction(transaction, owner);
            }
        }

        public String id() { return id; }
        public String lineage() { return lineage; }
        public ObservationComponent.Kind kind() { return kind; }
        public Outcome outcome() { return outcome; }
        public double mass() { return mass; }
        public int cellCount() { return logL0Density.length; }
        public double logL0DensityAt(int cell) { return logL0Density[cell]; }
    }

    /** Normalized measured scalar row; no support is distinct from measured zero density. */
    public static final class NormalizedDensity {
        private final double[] logL0Density;
        private final boolean hasSupport;

        private NormalizedDensity(double[] logL0Density, boolean hasSupport) {
            this.logL0Density = logL0Density;
            this.hasSupport = hasSupport;
        }

        public boolean hasSupport() { return hasSupport; }
        public int cellCount() { return logL0Density.length; }
        public double logL0DensityAt(int cell) { return logL0Density[cell]; }
    }

    /** Immutable reduced unary and posterior component rows, indexed without array exposure. */
    public static final class ReducedRows {
        private final double[] unaryCosts;
        private final double[] logMixtureDensities;
        private final double[][] responsibilities;

        private ReducedRows(double[] unaryCosts, double[] logMixtureDensities, double[][] responsibilities) {
            this.unaryCosts = unaryCosts;
            this.logMixtureDensities = logMixtureDensities;
            this.responsibilities = responsibilities;
        }

        public int stateCount() { return unaryCosts.length; }
        public int componentCount() { return responsibilities.length == 0 ? 0 : responsibilities[0].length; }
        public double unaryCost(int state) { return unaryCosts[state]; }
        /** Returns the true unfloored, unconditioned mixture log density at a cell. */
        public double logMixtureDensity(int state) { return logMixtureDensities[state]; }
        public double responsibility(int state, int component) { return responsibilities[state][component]; }
    }

    private final List<Component> rows;

    private BObservationMixture(List<Component> rows) {
        this.rows = rows;
    }

    /** Creates an immutable row inventory under an optional attempt owner. */
    public static BObservationMixture create(List<Component> rows, AttemptMemoryLedger.Owner owner) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Mixture rows are incomplete");
        }
        int cells = -1;
        double totalMass = 0.0;
        int missingCount = 0;
        for (int index = 0; index < rows.size(); index++) {
            Component row = rows.get(index);
            if (row == null) {
                throw new IllegalArgumentException("Mixture rows require unique identities and lineages");
            }
            for (int prior = 0; prior < index; prior++) {
                Component earlier = rows.get(prior);
                if (row.id().equals(earlier.id()) || row.lineage().equals(earlier.lineage())) {
                    throw new IllegalArgumentException("Mixture rows require unique identities and lineages");
                }
            }
            if (row.cellCount() == 0) {
                throw new IllegalArgumentException("Mixture rows require unique identities and lineages");
            }
            if (cells < 0) cells = row.cellCount();
            if (cells != row.cellCount()) throw new IllegalArgumentException("Mixture rows have different cell counts");
            if (row.kind() == ObservationComponent.Kind.MISSING) missingCount++;
            totalMass += row.mass();
        }
        if (missingCount != 1 || !Double.isFinite(totalMass)
                || Math.abs(totalMass - 1.0) > NORMALIZATION_TOLERANCE) {
            throw new IllegalArgumentException("Mixture requires one missing row and masses summing to one");
        }
        AttemptMemoryLedger.Owner transaction = transaction(owner, "mixture-inventory");
        try {
            long listCharge = Math.addExact(AttemptMemoryLedger.objectBytes(2L * Long.BYTES),
                    AttemptMemoryLedger.referenceArrayBytes(rows.size()));
            List<Component> copied = chargedObject(listCharge, () -> List.copyOf(rows), transaction);
            BObservationMixture mixture = chargedObject(AttemptMemoryLedger.objectBytes(Long.BYTES),
                    () -> new BObservationMixture(copied), transaction);
            commit(transaction, owner);
            return mixture;
        } finally {
            closeTransaction(transaction, owner);
        }
    }

    public int componentCount() { return rows.size(); }

    /** Returns the existing immutable component without exposing or copying its density array. */
    public Component component(int index) { return rows.get(index); }

    /** Normalizes measured nonnegative values against physical cell widths. */
    public static NormalizedDensity normalizeMeasured(double[] values, double[] cellWidthsMeters,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        if (values == null || cellWidthsMeters == null || values.length == 0
                || values.length != cellWidthsMeters.length || cancellation == null) {
            throw new IllegalArgumentException("Measured density inputs are incomplete");
        }
        boolean support = false;
        double maximumLogTerm = Double.NEGATIVE_INFINITY;
        double scaledIntegral = 0.0;
        for (int index = 0; index < values.length; index++) {
            cancellation.checkpoint();
            if (!Double.isFinite(values[index]) || values[index] < 0.0
                    || !Double.isFinite(cellWidthsMeters[index]) || cellWidthsMeters[index] <= 0.0) {
                throw new IllegalArgumentException("Measured density value or cell width is invalid");
            }
            if (values[index] > 0.0) {
                support = true;
                double term = StrictMath.log(values[index]) + StrictMath.log(cellWidthsMeters[index])
                        - LOG_REFERENCE_LENGTH;
                if (term > maximumLogTerm) {
                    scaledIntegral = maximumLogTerm == Double.NEGATIVE_INFINITY
                            ? 1.0 : scaledIntegral * StrictMath.exp(maximumLogTerm - term) + 1.0;
                    maximumLogTerm = term;
                } else {
                    scaledIntegral += StrictMath.exp(term - maximumLogTerm);
                }
            }
        }
        AttemptMemoryLedger.Owner transaction = transaction(owner, "normalize-mixture-row");
        try {
            double[] logs = chargedArray(values.length, transaction);
            java.util.Arrays.fill(logs, Double.NEGATIVE_INFINITY);
            if (support) {
                double logIntegral = maximumLogTerm + StrictMath.log(scaledIntegral);
                for (int index = 0; index < values.length; index++) {
                    cancellation.checkpoint();
                    if (values[index] > 0.0) {
                        logs[index] = LOG_REFERENCE_LENGTH + StrictMath.log(values[index]) - logIntegral;
                    }
                }
            }
            cancellation.checkpoint();
            boolean hasSupport = support;
            NormalizedDensity result = chargedObject(AttemptMemoryLedger.objectBytes(2L * Long.BYTES),
                    () -> new NormalizedDensity(logs, hasSupport), transaction);
            commit(transaction, owner);
            return result;
        } finally {
            closeTransaction(transaction, owner);
        }
    }

    /** Reduces component rows to a scalar unary and component responsibilities per lattice cell. */
    public ReducedRows reduce(ProbabilisticStateLattice lattice, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        if (lattice == null || cancellation == null) throw new IllegalArgumentException("Reduction input is incomplete");
        int stateCount = lattice.cells().size();
        int componentCount = rows.size();
        long work = Math.multiplyExact((long) stateCount, componentCount);
        if (work > MAX_REDUCTION_WORK) throw new IllegalArgumentException("Mixture reduction exceeds its work bound");
        if (lattice.components().size() != componentCount) {
            throw new IllegalArgumentException("Lattice and mixture component rows differ");
        }
        double domainWidth = 0.0;
        for (int state = 0; state < stateCount; state++) {
            cancellation.checkpoint();
            double width = lattice.cells().get(state).quadratureWidthMeters();
            if (!Double.isFinite(width) || width <= 0.0) throw new IllegalArgumentException("Invalid lattice width");
            domainWidth += width;
        }
        if (!Double.isFinite(domainWidth) || domainWidth <= 0.0) {
            throw new IllegalArgumentException("Lattice domain width is invalid");
        }
        int missingIndex = -1;
        for (int component = 0; component < componentCount; component++) {
            cancellation.checkpoint();
            Component row = rows.get(component);
            ObservationComponent latticeComponent = lattice.components().get(component);
            if (!row.id().equals(latticeComponent.id()) || !row.lineage().equals(latticeComponent.evidenceLineage())
                    || row.kind() != latticeComponent.kind() || row.cellCount() != stateCount) {
                throw new IllegalArgumentException("Mixture component identity/order differs from lattice");
            }
            double logIntegral = Double.NEGATIVE_INFINITY;
            boolean hasSupport = false;
            for (int state = 0; state < stateCount; state++) {
                cancellation.checkpoint();
                double logDensity = row.logL0DensityAt(state);
                if (logDensity != Double.NEGATIVE_INFINITY) {
                    hasSupport = true;
                    logIntegral = logAdd(logIntegral, logDensity
                            + StrictMath.log(lattice.cells().get(state).quadratureWidthMeters()) - LOG_REFERENCE_LENGTH);
                }
            }
            if (row.mass() > 0.0 && !hasSupport) {
                throw new IllegalArgumentException("Positive mixture mass has no density support");
            }
            if (row.kind() == ObservationComponent.Kind.CENSORED && !hasSupport) {
                throw new IllegalArgumentException("Baseline censored component must retain measured density support");
            }
            if (hasSupport && Math.abs(logIntegral) > NORMALIZATION_TOLERANCE) {
                throw new IllegalArgumentException("Mixture component is not quadrature normalized");
            }
            if (row.kind() == ObservationComponent.Kind.MISSING) {
                if (missingIndex >= 0) throw new IllegalArgumentException("Mixture has multiple missing rows");
                missingIndex = component;
                double uniformLog = LOG_REFERENCE_LENGTH - StrictMath.log(domainWidth);
                for (int state = 0; state < stateCount; state++) {
                    if (Math.abs(row.logL0DensityAt(state) - uniformLog) > NORMALIZATION_TOLERANCE) {
                        throw new IllegalArgumentException("Missing row must be uniform over the full lattice domain");
                    }
                }
            }
        }
        if (missingIndex < 0) throw new IllegalArgumentException("Mixture requires an explicit missing row");

        AttemptMemoryLedger.Owner transaction = transaction(owner, "reduce-observation-mixture");
        try {
            double[] unary = chargedArray(stateCount, transaction);
            double[] logMixtureDensities = chargedArray(stateCount, transaction);
            double[][] responsibilities = chargedObject(
                    AttemptMemoryLedger.referenceArrayBytes(stateCount), () -> new double[stateCount][], transaction);
            for (int state = 0; state < stateCount; state++) {
                cancellation.checkpoint();
                double[] rowResponsibilities = chargedArray(componentCount, transaction);
                responsibilities[state] = rowResponsibilities;
                double maximum = Double.NEGATIVE_INFINITY;
                double scaledSum = 0.0;
                for (int component = 0; component < componentCount; component++) {
                    cancellation.checkpoint();
                    Component componentRow = rows.get(component);
                    double logTerm = componentRow.mass() == 0.0 ? Double.NEGATIVE_INFINITY
                            : StrictMath.log(componentRow.mass()) + componentRow.logL0DensityAt(state);
                    rowResponsibilities[component] = logTerm;
                    if (logTerm > maximum) {
                        scaledSum = maximum == Double.NEGATIVE_INFINITY
                                ? 1.0 : scaledSum * StrictMath.exp(maximum - logTerm) + 1.0;
                        maximum = logTerm;
                    } else if (logTerm != Double.NEGATIVE_INFINITY) {
                        scaledSum += StrictMath.exp(logTerm - maximum);
                    }
                }
                double logMixture = maximum == Double.NEGATIVE_INFINITY
                        ? Double.NEGATIVE_INFINITY : maximum + StrictMath.log(scaledSum);
                logMixtureDensities[state] = logMixture;
                unary[state] = lattice.cells().get(state).exactAnchor() ? 0.0
                        : -Math.max(LOG_LIKELIHOOD_FLOOR, logMixture);
                if (logMixture != Double.NEGATIVE_INFINITY) {
                    for (int component = 0; component < componentCount; component++) {
                        cancellation.checkpoint();
                        double logTerm = rowResponsibilities[component];
                        rowResponsibilities[component] = logTerm == Double.NEGATIVE_INFINITY
                                ? 0.0 : StrictMath.exp(logTerm - logMixture);
                    }
                } else {
                    java.util.Arrays.fill(rowResponsibilities, 0.0);
                }
            }
            cancellation.checkpoint();
            ReducedRows result = chargedObject(AttemptMemoryLedger.objectBytes(3L * Long.BYTES),
                    () -> new ReducedRows(unary, logMixtureDensities, responsibilities), transaction);
            commit(transaction, owner);
            return result;
        } finally {
            closeTransaction(transaction, owner);
        }
    }

    private static double logAdd(double left, double right) {
        if (left == Double.NEGATIVE_INFINITY) return right;
        if (right == Double.NEGATIVE_INFINITY) return left;
        double maximum = Math.max(left, right);
        return maximum + StrictMath.log(StrictMath.exp(left - maximum) + StrictMath.exp(right - maximum));
    }

    private static boolean allNegativeInfinity(double[] values) {
        for (double value : values) if (value != Double.NEGATIVE_INFINITY) return false;
        return true;
    }

    /** Legacy analytic convenience maps an empty measured row to observed zero excess. */
    private static Outcome defaultOutcome(ObservationComponent.Kind kind, double mass, double[] logDensity) {
        if (kind == null || logDensity == null) return null;
        return switch (kind) {
            case MISSING -> Outcome.BASELINE_MISSING;
            case CENSORED -> Outcome.BASELINE_CENSORED;
            case MEASURED -> mass == 0.0 && allNegativeInfinity(logDensity)
                    ? Outcome.OBSERVED_ZERO_EXCESS : Outcome.MEASURED;
        };
    }

    private static void validateOutcome(ObservationComponent.Kind kind, Outcome outcome,
            double mass, double[] logDensity) {
        switch (outcome) {
            case BASELINE_MISSING -> {
                if (kind != ObservationComponent.Kind.MISSING) {
                    throw new IllegalArgumentException("Baseline missing outcome requires a missing component");
                }
            }
            case BASELINE_CENSORED -> {
                if (kind != ObservationComponent.Kind.CENSORED) {
                    throw new IllegalArgumentException("Baseline censored outcome requires a censored component");
                }
            }
            case MEASURED -> {
                if (kind != ObservationComponent.Kind.MEASURED || allNegativeInfinity(logDensity)) {
                    throw new IllegalArgumentException("Measured outcome requires measured support");
                }
            }
            case OBSERVED_ZERO_EXCESS, NO_ADMITTED_TENT_SUPPORT,
                    UNAVAILABLE_ORIGINAL_RECEIPT, UNAVAILABLE_SOURCE_PITCH, GROUPED_PARENT_UNAVAILABLE -> {
                if (kind != ObservationComponent.Kind.MEASURED || mass != 0.0 || !allNegativeInfinity(logDensity)) {
                    throw new IllegalArgumentException("Zero-support measured outcome requires zero mass and no density");
                }
            }
        }
    }

    private static AttemptMemoryLedger.Owner transaction(AttemptMemoryLedger.Owner owner, String name) {
        return owner == null ? null : owner.child(name);
    }

    private static void commit(AttemptMemoryLedger.Owner transaction, AttemptMemoryLedger.Owner owner) {
        if (transaction != null) transaction.transferTo(owner);
    }

    private static void closeTransaction(AttemptMemoryLedger.Owner transaction, AttemptMemoryLedger.Owner owner) {
        if (transaction != null) transaction.close();
    }

    private static double[] chargedArrayCopy(double[] source, AttemptMemoryLedger.Owner owner) {
        if (owner == null) return source.clone();
        try (AttemptMemoryLedger.Reservation reservation = owner.reserve(
                AttemptMemoryLedger.arrayBytes(source.length, Double.BYTES))) {
            double[] copy = source.clone();
            reservation.adopt(copy);
            return copy;
        }
    }

    private static double[] chargedArray(int length, AttemptMemoryLedger.Owner owner) {
        if (owner == null) return new double[length];
        try (AttemptMemoryLedger.Reservation reservation = owner.reserve(
                AttemptMemoryLedger.arrayBytes(length, Double.BYTES))) {
            double[] result = new double[length];
            reservation.adopt(result);
            return result;
        }
    }

    private static <T> T chargedObject(long bytes, Supplier<T> allocator, AttemptMemoryLedger.Owner owner) {
        if (owner == null) return allocator.get();
        try (AttemptMemoryLedger.Reservation reservation = owner.reserve(bytes)) {
            T result = allocator.get();
            reservation.adopt(result);
            return result;
        }
    }
}
