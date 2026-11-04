package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.AcceptedModeThresholdReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.AcceptedThresholdExtraction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.Mode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.Sample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/**
 * Experimental, input-owned admission of actual straight native connectors.
 * Certifies measured continuation at declared resolution, not continuous critical topology
 * or semantic branch ownership. No engine or UI enables this service implicitly.
 * Returned receipts borrow this session's retained lifetime; close it before closing its input.
 */
public final class NativeConnectorEvaluator implements AutoCloseable {
    public enum QueryPolicy {
        DENSE_EIGHTH_NATIVE_V1, HALF_NATIVE_L0_V1, PIXEL_CAPPED_L0_V1, ZERO_GUARDED_LOCAL_V1
    }
    public enum ExecutionPolicy { EAGER_ALL_PROBES_V1, STOP_ON_FINAL_MODE_FAILURE_V1 }
    public enum SourceClass { OBSERVED, GAP, CENSORED }
    /** Status of an ordinary leg's native geometric reference, not a claim of image support. */
    public enum NativeReferenceStatus { AVAILABLE, UNAVAILABLE }
    /** Original row state or exact protected source occurrence; never a fabricated boundary row. */
    public sealed interface PhysicalEndpoint permits RowState, Barrier { }
    /** Exact reachable native row-family ceiling; the state set is never truncated. */
    public static final class StateFamilyLimitException extends RuntimeException {
        private final int originalOrdinal;
        private final int limit;
        private StateFamilyLimitException(int originalOrdinal, int limit) {
            super("Native emission state family limit exceeded");
            this.originalOrdinal = originalOrdinal; this.limit = limit;
        }
        public int originalOrdinal() { return originalOrdinal; }
        public int limit() { return limit; }
    }
    public enum Status { ADMITTED, REJECTED, UNAVAILABLE }
    public enum Reason {
        NONE, FOREIGN_STATE, INVALID_STATE, SPAN, MANDATORY_BARRIER, NATIVE_PITCH_UNAVAILABLE,
        SOURCE_INELIGIBLE, DECISION_AUTHORITY, ACQUISITION_AUTHORITY, SCALAR_SUPPORT,
        NO_COMPLETE_MODE, AMBIGUOUS_MODE, INCOMPLETE_FLANKS, MISSING_THRESHOLD,
        CENSORED_OR_SUPPRESSED_COMPETITOR, NONUNIQUE_CONTINUATION, ENDPOINT_OWNERSHIP,
        SCALAR_BELOW_ENVELOPE, INCOMING_DIRECTION_UNAVAILABLE, LOCAL_NO_MODE
    }

    public enum ProfileBackend { FULL_WINDOW, LOCAL, DENSE_FALLBACK }
    public enum FallbackCause {
        NONE, INITIAL_WINDOW_TOO_WIDE, ZERO_GUARD_UNAVAILABLE, NOISE_FLOOR_NONZERO,
        LOCAL_MODE_UNRESOLVED, COMPETITOR_SUPPORT_UNAVAILABLE, CROSS_PROBE_SUPPORT_UNAVAILABLE
    }

    /** Immutable provenance for the actual transverse profile samples retained by one probe. */
    public record ProfileQueryEvidence(ProfileBackend backend, double halfWindowMeters,
            int sampleCount, int expansionCount, FallbackCause fallbackCause,
            int totalSampleQueries,
            boolean localNoModeProven) {
        public ProfileQueryEvidence {
            Objects.requireNonNull(backend); Objects.requireNonNull(fallbackCause);
            if (!Double.isFinite(halfWindowMeters) || halfWindowMeters < 0 || sampleCount < 0
                    || totalSampleQueries < sampleCount
                    || expansionCount < 0
                    || backend == ProfileBackend.FULL_WINDOW
                        && (fallbackCause != FallbackCause.NONE || expansionCount != 0 || localNoModeProven)
                    || backend == ProfileBackend.LOCAL && fallbackCause != FallbackCause.NONE
                    || backend == ProfileBackend.DENSE_FALLBACK
                        && (fallbackCause == FallbackCause.NONE || localNoModeProven))
                throw new IllegalArgumentException("Transverse profile query evidence is inconsistent");
        }
    }

    /** Exact native enumeration identity; peak owns the underlying band, not necessarily offset. */
    public record RowState(int row, int nativeStateId, double offsetRasterPixels,
            SourceClass sourceClass, CrossSectionPeak peakOwner) implements PhysicalEndpoint {
        public RowState {
            if (row < 0 || nativeStateId < 0 || !Double.isFinite(offsetRasterPixels) || sourceClass == null)
                throw new IllegalArgumentException("Native row/state identity is incomplete");
        }
    }

    /** Issued physical orientation for an already admitted ordinary native transition. */
    public static final class OrdinaryPhysicalLeg {
        private final PhysicalEndpoint from;
        private final PhysicalEndpoint to;
        private final RasterPoint fromRaster;
        private final RasterPoint toRaster;
        private final MetricPoint fromMetric;
        private final MetricPoint toMetric;
        private final double lengthMeters;
        private final NativeReferenceStatus nativeReferenceStatus;

        private OrdinaryPhysicalLeg(PhysicalEndpoint from, PhysicalEndpoint to,
                RasterPoint fromRaster, RasterPoint toRaster,
                MetricPoint fromMetric, MetricPoint toMetric, double lengthMeters,
                NativeReferenceStatus nativeReferenceStatus) {
            this.from = from; this.to = to; this.fromRaster = fromRaster; this.toRaster = toRaster;
            this.fromMetric = fromMetric; this.toMetric = toMetric; this.lengthMeters = lengthMeters;
            this.nativeReferenceStatus = nativeReferenceStatus;
        }
        public PhysicalEndpoint fromEndpoint() { return from; }
        public PhysicalEndpoint toEndpoint() { return to; }
        /** Compatibility accessor for a row-owned physical origin. */
        public RowState from() {
            if (from instanceof RowState row) return row;
            throw new IllegalStateException("Physical origin is a protected occurrence, not a row");
        }
        /** Compatibility accessor for a row-owned physical destination. */
        public RowState to() {
            if (to instanceof RowState row) return row;
            throw new IllegalStateException("Physical destination is a protected occurrence, not a row");
        }
        public RasterPoint fromRaster() { return fromRaster; }
        public RasterPoint toRaster() { return toRaster; }
        public MetricPoint fromMetric() { return fromMetric; }
        public MetricPoint toMetric() { return toMetric; }
        public double lengthMeters() { return lengthMeters; }
        public NativeReferenceStatus nativeReferenceStatus() { return nativeReferenceStatus; }
    }

    /** Exact protected event with its two immediately bracketing mandatory source rows. */
    public record Barrier(String occurrenceId, int leftRow, int rightRow, double sourceChainageMeters,
            MetricPoint exactPosition) implements PhysicalEndpoint {
        public Barrier {
            if (occurrenceId == null || occurrenceId.isBlank() || leftRow < 0 || rightRow <= leftRow
                    || !Double.isFinite(sourceChainageMeters) || exactPosition == null)
                throw new IllegalArgumentException("Protected connector barrier is incomplete");
        }
    }

    /** Effective positive direction plus current source cursor after ordinary zero transitions. */
    public static final class PhysicalDirectionCursor {
        private final Receipt optionalOrigin;
        private final OrdinaryPhysicalLeg ordinaryOrigin;
        private final PhysicalEndpoint cursor;
        private PhysicalDirectionCursor(Receipt optionalOrigin, OrdinaryPhysicalLeg ordinaryOrigin,
                PhysicalEndpoint cursor) {
            this.optionalOrigin = optionalOrigin; this.ordinaryOrigin = ordinaryOrigin; this.cursor = cursor;
        }
        public Optional<Receipt> optionalOrigin() { return Optional.ofNullable(optionalOrigin); }
        public Optional<OrdinaryPhysicalLeg> ordinaryOrigin() { return Optional.ofNullable(ordinaryOrigin); }
        public PhysicalEndpoint cursor() { return cursor; }
    }

    /**
     * Immutable caller-owned inventory declaration. Later engine adapters must populate all actual
     * states/events from their native constructors. This declaration is not engine-authority attestation.
     * Caller owns construction; the evaluator reserves its execution copy before allocating it.
     */
    public record Domain(List<RowState> states, List<Integer> mandatoryRows, List<Barrier> barriers,
            Map<Integer, Integer> originalOrdinalByContextRow) {
        public Domain(List<RowState> states, List<Integer> mandatoryRows) {
            this(states, mandatoryRows, List.of(), Map.of());
        }
        public Domain(List<RowState> states, List<Integer> mandatoryRows, List<Barrier> barriers) {
            this(states, mandatoryRows, barriers, Map.of());
        }
        public Domain {
            states = List.copyOf(states);
            mandatoryRows = List.copyOf(mandatoryRows);
            barriers = List.copyOf(barriers);
            originalOrdinalByContextRow = Map.copyOf(originalOrdinalByContextRow);
        }
    }

    /** One actual transverse query; all accepted, censored and suppressed alternatives are retained. */
    public record Probe(double parameter, RasterPoint rasterPosition, MetricPoint metricPosition,
            MetricPoint metricNormal, double nativePitchMeters, List<Sample> samples,
            AcceptedThresholdExtraction extraction, Mode selectedMode,
            AcceptedModeThresholdReceipt threshold, ProfileQueryEvidence profileQuery) {
        public Probe {
            samples = List.copyOf(samples);
            Objects.requireNonNull(profileQuery);
            if (profileQuery.sampleCount() != samples.size())
                throw new IllegalArgumentException("Profile query sample count does not match retained samples");
        }
    }

    /** Scalar validity and measured native continuation have separate explicit success fields. */
    public record Receipt(Status status, Reason reason, OptionalEmissionContext context,
            RowState from, RowState to, QueryPolicy queryPolicy, String parameterVersion,
            double sourceSpanMeters, double emittedLengthMeters, double minimumNativePitchMeters,
            Optional<BilinearSegmentIntegral.Result> scalar, boolean scalarEnvelopeComplete,
            boolean measuredNativeContinuationComplete, List<Probe> probes, int plannedProbeCount,
            int firstFailingSlabIndex,
            Optional<Receipt> transportedIncoming, Optional<OrdinaryPhysicalLeg> transportedOrdinary) {
        public Receipt(Status status, Reason reason, OptionalEmissionContext context, RowState from, RowState to,
                QueryPolicy queryPolicy, String parameterVersion, double sourceSpanMeters, double emittedLengthMeters,
                double minimumNativePitchMeters, Optional<BilinearSegmentIntegral.Result> scalar,
                boolean scalarEnvelopeComplete, boolean measuredNativeContinuationComplete, List<Probe> probes,
                Optional<Receipt> transportedIncoming) {
            this(status, reason, context, from, to, queryPolicy, parameterVersion, sourceSpanMeters,
                emittedLengthMeters, minimumNativePitchMeters, scalar, scalarEnvelopeComplete,
                measuredNativeContinuationComplete, probes, -1, -1, transportedIncoming, Optional.empty());
        }
        public Receipt(Status status, Reason reason, OptionalEmissionContext context, RowState from, RowState to,
                QueryPolicy queryPolicy, String parameterVersion, double sourceSpanMeters, double emittedLengthMeters,
                double minimumNativePitchMeters, Optional<BilinearSegmentIntegral.Result> scalar,
                boolean scalarEnvelopeComplete, boolean measuredNativeContinuationComplete, List<Probe> probes,
                int plannedProbeCount, int firstFailingSlabIndex, Optional<Receipt> transportedIncoming) {
            this(status, reason, context, from, to, queryPolicy, parameterVersion, sourceSpanMeters,
                emittedLengthMeters, minimumNativePitchMeters, scalar, scalarEnvelopeComplete,
                measuredNativeContinuationComplete, probes, plannedProbeCount, firstFailingSlabIndex,
                transportedIncoming, Optional.empty());
        }
        public Receipt {
            Objects.requireNonNull(status); Objects.requireNonNull(reason); Objects.requireNonNull(context);
            Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(queryPolicy);
            Objects.requireNonNull(parameterVersion);
            scalar = Objects.requireNonNull(scalar); transportedIncoming = Objects.requireNonNull(transportedIncoming);
            transportedOrdinary = Objects.requireNonNull(transportedOrdinary);
            if (transportedIncoming.isPresent() && transportedOrdinary.isPresent())
                throw new IllegalArgumentException("A zero edge cannot transport two physical legs");
            probes = List.copyOf(probes);
            if (plannedProbeCount < -1 || firstFailingSlabIndex < -1
                    || plannedProbeCount >= 0 && probes.size() > plannedProbeCount
                    || firstFailingSlabIndex >= 0 && firstFailingSlabIndex >= Math.max(1, probes.size()-1))
                throw new IllegalArgumentException("Connector progress provenance is inconsistent");
            if (status == Status.ADMITTED && (reason != Reason.NONE || scalar.isEmpty()
                    || scalar.get().status() != BilinearSegmentIntegral.Status.COMPLETE
                    || !scalarEnvelopeComplete || !measuredNativeContinuationComplete)
                    || status != Status.ADMITTED && reason == Reason.NONE)
                throw new IllegalArgumentException("Connector admission receipt is inconsistent");
        }
        /** Source measure for Q; emitted physical coverage uses emittedLengthMeters separately. */
        public double sourceWeightedLoss() {
            if (status != Status.ADMITTED) throw new IllegalStateException("Connector was not admitted");
            return sourceSpanMeters / context.nativePitchMeters().orElseThrow()
                * (1 - scalar.orElseThrow().mean().orElseThrow());
        }
    }

    private final SampledEmissionInput input;
    private final OptionalEmissionContext context;
    private final EmissionWorkBudget budget;
    private final int stateFamilyLimit;
    private final AttemptMemoryLedger.Owner owner;
    private final QueryPolicy queryPolicy;
    private final ExecutionPolicy executionPolicy;
    private final IdentityHashMap<RowState, Boolean> states;
    private final List<Integer> mandatoryRows;
    private final List<Barrier> barriers;
    private final Map<Integer, Integer> originalOrdinalByContextRow;
    private final EvidenceModelParameters parameters = EvidenceModelParameters.defaults();
    private final LocalScalarProfileExtractor extractor = new LocalScalarProfileExtractor();
    private RuntimeException terminalFailure;

    public NativeConnectorEvaluator(SampledEmissionInput input, Domain domain) {
        this(input, domain, QueryPolicy.DENSE_EIGHTH_NATIVE_V1);
    }

    public NativeConnectorEvaluator(SampledEmissionInput input, Domain domain, QueryPolicy queryPolicy) {
        this(input,domain,queryPolicy,ExecutionPolicy.EAGER_ALL_PROBES_V1);
    }

    public NativeConnectorEvaluator(SampledEmissionInput input, Domain domain, QueryPolicy queryPolicy,
            ExecutionPolicy executionPolicy) {
        this.input = Objects.requireNonNull(input);
        Objects.requireNonNull(domain);
        this.queryPolicy = Objects.requireNonNull(queryPolicy, "queryPolicy");
        this.executionPolicy=Objects.requireNonNull(executionPolicy,"executionPolicy");
        if(executionPolicy==ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1
                && queryPolicy!=QueryPolicy.ZERO_GUARDED_LOCAL_V1)
            throw new IllegalArgumentException("Stop-on-final-mode-failure requires the zero-guarded local query policy");
        owner = input.openExecutionOwner("native-connector");
        context = input.context(); budget = input.workBudget();
        stateFamilyLimit = budget.stateFamilyLimit();
        originalOrdinalByContextRow = domain.originalOrdinalByContextRow();
        try {
            // IdentityHashMap explicit sizing: at most 8 references per declaration is conservative.
            reserve(owner, Math.addExact(Math.addExact(2048L,
                Math.multiplyExact(160L, domain.states().size())),
                Math.addExact(Math.multiplyExact(80L,
                    (long) domain.mandatoryRows().size() + domain.barriers().size()),
                    Math.multiplyExact(64L, originalOrdinalByContextRow.size()))));
            for (var binding : originalOrdinalByContextRow.entrySet()) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (binding.getKey() < 0 || binding.getKey() >= context.observations().size()
                        || binding.getValue() < 0)
                    throw new IllegalArgumentException("Original ordinal binding is outside the retained source");
                for (var other : originalOrdinalByContextRow.entrySet()) {
                    work(EmissionWorkBudget.Operation.PREPARATION, 1);
                    if (binding.getKey() < other.getKey() && binding.getValue() >= other.getValue())
                        throw new IllegalArgumentException("Original ordinals must increase with retained rows");
                }
            }
            if (!originalOrdinalByContextRow.isEmpty()) for (RowState state : domain.states()) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (!originalOrdinalByContextRow.containsKey(state.row()))
                    throw new IllegalArgumentException("Native state has no original observation ordinal");
            }
            work(EmissionWorkBudget.Operation.PREPARATION, domain.states().size());
            states = new IdentityHashMap<>(domain.states().size());
            // The existing reservation includes this primitive identity scratch array.
            work(EmissionWorkBudget.Operation.ALLOCATION, 1);
            long[] nativeIds = new long[domain.states().size()];
            int nativeIdIndex = 0;
            for (RowState state : domain.states()) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (states.put(state, Boolean.TRUE) != null || state.row() >= context.observations().size())
                    throw new IllegalArgumentException("Duplicate or out-of-range native state");
                nativeIds[nativeIdIndex++] = ((long) state.row() << 32) | state.nativeStateId();
            }
            work(EmissionWorkBudget.Operation.PREPARATION, Math.multiplyExact((long) nativeIds.length,
                Math.max(1, 64 - Long.numberOfLeadingZeros(nativeIds.length))));
            Arrays.sort(nativeIds);
            int family = nativeIds.length == 0 ? 0 : 1;
            for (int index = 1; index < nativeIds.length; index++) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (nativeIds[index] == nativeIds[index - 1])
                    throw new IllegalArgumentException("Duplicate native row/state identity");
                family = nativeIds[index] >>> 32 == nativeIds[index - 1] >>> 32 ? family + 1 : 1;
                if (family > stateFamilyLimit) throw new StateFamilyLimitException(
                    originalOrdinal((int) (nativeIds[index] >>> 32)), stateFamilyLimit);
            }
            mandatoryRows = List.copyOf(domain.mandatoryRows()); barriers = List.copyOf(domain.barriers());
            for (int row : mandatoryRows) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (row < 0 || row >= context.observations().size()
                        || !originalOrdinalByContextRow.isEmpty()
                            && !originalOrdinalByContextRow.containsKey(row))
                    throw new IllegalArgumentException("Mandatory row is outside the source");
            }
            for (int barrierIndex = 0; barrierIndex < barriers.size(); barrierIndex++) {
                Barrier barrier = barriers.get(barrierIndex);
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (barrier.rightRow() >= context.observations().size()
                        || !originalOrdinalByContextRow.isEmpty()
                            && (!originalOrdinalByContextRow.containsKey(barrier.leftRow())
                                || !originalOrdinalByContextRow.containsKey(barrier.rightRow()))
                        || originalOrdinal(barrier.rightRow()) != originalOrdinal(barrier.leftRow()) + 1
                        || chainage(barrier.leftRow()) > barrier.sourceChainageMeters()
                        || chainage(barrier.rightRow()) < barrier.sourceChainageMeters())
                    throw new IllegalArgumentException("Protected event does not match its source bracket");
                for (int priorIndex = 0; priorIndex < barrierIndex; priorIndex++) {
                    work(EmissionWorkBudget.Operation.PREPARATION, 1);
                    Barrier prior = barriers.get(priorIndex);
                    if (prior.occurrenceId().equals(barrier.occurrenceId())
                            || prior.leftRow() > barrier.leftRow()
                            || prior.sourceChainageMeters() == barrier.sourceChainageMeters()
                                && !prior.exactPosition().equals(barrier.exactPosition())
                            || prior.leftRow() == barrier.leftRow()
                                && (prior.rightRow() != barrier.rightRow()
                                    || prior.sourceChainageMeters() > barrier.sourceChainageMeters()))
                        throw new IllegalArgumentException("Protected occurrence order or identity conflicts");
                }
            }
        } catch (RuntimeException failure) {
            owner.close();
            if (failure instanceof StateFamilyLimitException
                    || failure instanceof EmissionWorkBudget.LimitExceededException
                    || failure instanceof AttemptMemoryLedger.ResourceLimitException
                    || failure instanceof CancellationException) input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    public Receipt evaluate(RowState from, RowState to) { return evaluate(from, to, null); }

    /** Registers one reachable native family member without predeclaring potential carry origins. */
    public RowState registerState(RowState state, int originalOrdinal) {
        requireLive();
        Objects.requireNonNull(state);
        try {
            input.requireOpenAndUnchanged();
            work(EmissionWorkBudget.Operation.PAIR_INITIALIZATION, 1);
            if (state.row() >= context.observations().size()
                    || !originalOrdinalByContextRow.isEmpty()
                        && !originalOrdinalByContextRow.containsKey(state.row())
                    || originalOrdinal(state.row()) != originalOrdinal)
                throw new IllegalArgumentException("Reachable native state has no exact original row binding");
            int family = 0;
            for (RowState existing : states.keySet()) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (existing == state || existing.row() == state.row()
                        && existing.nativeStateId() == state.nativeStateId())
                    throw new IllegalArgumentException("Duplicate native row/state identity");
                if (originalOrdinal(existing.row()) == originalOrdinal) family++;
            }
            if (family >= stateFamilyLimit) throw new StateFamilyLimitException(originalOrdinal,
                stateFamilyLimit);
            work(EmissionWorkBudget.Operation.ALLOCATION, 1);
            try (var allocation = owner.child("reachable-native-state")) {
                work(EmissionWorkBudget.Operation.ALLOCATION, 1);
                var reservation = allocation.reserve(256);
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                reservation.adopt(state);
                allocation.transferTo(owner);
                states.put(state, Boolean.TRUE);
                return state;
            }
        } catch (StateFamilyLimitException | EmissionWorkBudget.LimitExceededException
                | AttemptMemoryLedger.ResourceLimitException | CancellationException failure) {
            terminalFailure = failure;
            input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    /** Records actual positive geometry after the caller attests ordinary native admission.
     * This token does not attest image support, a measured Q, or optional admission. */
    public OrdinaryPhysicalLeg registerOrdinaryPhysicalLeg(RowState from, RowState to,
            NativeReferenceStatus nativeReferenceStatus) {
        return issueOrdinaryLeg(from, to, nativeReferenceStatus);
    }

    private OrdinaryPhysicalLeg issueOrdinaryLeg(PhysicalEndpoint from, PhysicalEndpoint to,
            NativeReferenceStatus nativeReferenceStatus) {
        requireLive();
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(nativeReferenceStatus);
        try {
            input.requireOpenAndUnchanged();
            work(EmissionWorkBudget.Operation.PAIR_INITIALIZATION, 1);
            if (!ordinaryAdjacent(from, to))
                throw new IllegalArgumentException("Ordinary leg requires adjacent owned source occurrences");
            work(EmissionWorkBudget.Operation.ALLOCATION, 1);
            try (var allocation = owner.child("ordinary-leg")) {
                work(EmissionWorkBudget.Operation.ALLOCATION, 1);
                var reservation = allocation.reserve(320);
                RasterPoint fromRaster = endpointRaster(from), toRaster = endpointRaster(to);
                MetricPoint fromMetric = endpointMetric(from), toMetric = endpointMetric(to);
                double length = fromMetric.distanceTo(toMetric);
                if (!(length > 0) || !Double.isFinite(length))
                    throw new IllegalArgumentException("Ordinary physical leg must have finite positive length");
                var result = new OrdinaryPhysicalLeg(from, to, fromRaster, toRaster, fromMetric, toMetric,
                    length, nativeReferenceStatus);
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                reservation.adopt(result);
                allocation.transferTo(owner);
                issuedOrdinary.put(result, Boolean.TRUE);
                return result;
            }
        } catch (EmissionWorkBudget.LimitExceededException | AttemptMemoryLedger.ResourceLimitException
                | CancellationException failure) {
            terminalFailure = failure;
            input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    /** Zero-edge entry for a session-owned ordinary physical leg. */
    public Receipt evaluateWithOrdinaryIncoming(RowState from, RowState to, OrdinaryPhysicalLeg incoming) {
        return evaluateInternal(from, to, null, Objects.requireNonNull(incoming), null);
    }

    /** Advances a native ordinary zero edge while retaining an issued positive ordinary direction. */
    public PhysicalDirectionCursor advanceOrdinaryZero(OrdinaryPhysicalLeg incoming,
            PhysicalEndpoint from, PhysicalEndpoint to) {
        return advanceOrdinaryZeroInternal(null, Objects.requireNonNull(incoming), null, from, to);
    }

    /** Advances a native ordinary zero edge while retaining an issued positive optional direction. */
    public PhysicalDirectionCursor advanceOrdinaryZero(Receipt incoming,
            PhysicalEndpoint from, PhysicalEndpoint to) {
        return advanceOrdinaryZeroInternal(Objects.requireNonNull(incoming), null, null, from, to);
    }

    /** Advances another ordinary zero edge using the same effective positive direction. */
    public PhysicalDirectionCursor advanceOrdinaryZero(PhysicalDirectionCursor incoming,
            PhysicalEndpoint from, PhysicalEndpoint to) {
        return advanceOrdinaryZeroInternal(null, null, Objects.requireNonNull(incoming), from, to);
    }

    private PhysicalDirectionCursor advanceOrdinaryZeroInternal(Receipt receipt,
            OrdinaryPhysicalLeg ordinary, PhysicalDirectionCursor prior,
            PhysicalEndpoint from, PhysicalEndpoint to) {
        requireLive();
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        try {
            input.requireOpenAndUnchanged();
            work(EmissionWorkBudget.Operation.TRANSPORT, 1);
            Receipt positiveOptional = null;
            OrdinaryPhysicalLeg positiveOrdinary = null;
            if (receipt != null) {
                if (!issuedHere(receipt) || receipt.status() != Status.ADMITTED
                        || receipt.context() != context || receipt.to() != from)
                    throw new IllegalArgumentException("Incoming optional receipt is not this cursor's direction");
                positiveOptional = receipt.emittedLengthMeters() > 0
                    ? receipt : receipt.transportedIncoming().orElse(null);
                if (positiveOptional == null) positiveOrdinary = receipt.transportedOrdinary().orElse(null);
            } else if (ordinary != null) {
                if (!issuedOrdinaryHere(ordinary) || ordinary.toEndpoint() != from)
                    throw new IllegalArgumentException("Incoming ordinary leg is not this cursor's direction");
                positiveOrdinary = ordinary;
            } else {
                if (!issuedDirectionHere(prior) || prior.cursor() != from)
                    throw new IllegalArgumentException("Incoming direction cursor is foreign or misplaced");
                positiveOptional = prior.optionalOrigin().orElse(null);
                positiveOrdinary = prior.ordinaryOrigin().orElse(null);
            }
            if (positiveOptional == null && (positiveOrdinary == null
                    || !issuedOrdinaryHere(positiveOrdinary)))
                throw new IllegalArgumentException("Ordinary zero transport has no positive direction");
            if (!ordinaryAdjacent(from, to))
                throw new IllegalArgumentException("Ordinary zero endpoints are not adjacent owned occurrences");
            MetricPoint a = endpointMetric(from), b = endpointMetric(to);
            if (!a.equals(b))
                throw new IllegalArgumentException("Ordinary zero transport must preserve the exact physical point");
            work(EmissionWorkBudget.Operation.ALLOCATION, 1);
            try (var allocation = owner.child("ordinary-zero-cursor")) {
                work(EmissionWorkBudget.Operation.ALLOCATION, 1);
                var reservation = allocation.reserve(192);
                var result = new PhysicalDirectionCursor(positiveOptional, positiveOrdinary, to);
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                reservation.adopt(result);
                allocation.transferTo(owner);
                issuedDirections.put(result, Boolean.TRUE);
                return result;
            }
        } catch (EmissionWorkBudget.LimitExceededException | AttemptMemoryLedger.ResourceLimitException
                | CancellationException failure) {
            terminalFailure = failure;
            input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    /** Uses an issued cursor to orient the next optional zero edge. */
    public Receipt evaluateWithDirectionCursor(RowState from, RowState to, PhysicalDirectionCursor incoming) {
        return evaluateInternal(from, to, null, null, Objects.requireNonNull(incoming));
    }

    /** Records an ordinary physical leg from an exact protected occurrence to its right row. */
    public OrdinaryPhysicalLeg registerOrdinaryPhysicalLeg(Barrier from, RowState to,
            NativeReferenceStatus nativeReferenceStatus) {
        return issueOrdinaryLeg(from, to, nativeReferenceStatus);
    }

    /** Records an ordinary physical leg from a left row to its exact protected occurrence. */
    public OrdinaryPhysicalLeg registerOrdinaryPhysicalLeg(RowState from, Barrier to,
            NativeReferenceStatus nativeReferenceStatus) {
        return issueOrdinaryLeg(from, to, nativeReferenceStatus);
    }

    /** Records an ordinary leg between consecutive exact events in one source bracket. */
    public OrdinaryPhysicalLeg registerOrdinaryPhysicalLeg(Barrier from, Barrier to,
            NativeReferenceStatus nativeReferenceStatus) {
        return issueOrdinaryLeg(from, to, nativeReferenceStatus);
    }

    /** For a zero edge, incoming must be the preceding same-session receipt, including zero transport. */
    public Receipt evaluate(RowState from, RowState to, Receipt incoming) {
        return evaluateInternal(from, to, incoming, null, null);
    }

    private Receipt evaluateInternal(RowState from, RowState to, Receipt incoming,
            OrdinaryPhysicalLeg incomingOrdinary, PhysicalDirectionCursor incomingDirection) {
        requireLive();
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        try {
            input.requireOpenAndUnchanged();
            work(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
            reserve(owner, 384); // A bounded result shell is charged even on early refusal.
            if (!states.containsKey(from) || !states.containsKey(to))
                return receipt(Status.UNAVAILABLE, Reason.FOREIGN_STATE, from, to, 0, 0, 0, null, false, false, List.of(), null);
            double span = chainage(to.row()) - chainage(from.row());
            if (to.row() <= from.row() || originalOrdinal(to.row()) - originalOrdinal(from.row()) - 1 > 16
                    || span < 0 || context.sourceSpanUpperMeters(from.row(), to.row()) > 20)
                return receipt(Status.REJECTED, Reason.SPAN, from, to, span, 0, 0, null, false, false, List.of(), null);
            if (barrierBetween(from.row(), to.row()))
                return receipt(Status.REJECTED, Reason.MANDATORY_BARRIER, from, to, span, 0, 0, null, false, false, List.of(), null);
            if (!validState(from) || !validState(to))
                return receipt(Status.UNAVAILABLE, Reason.INVALID_STATE, from, to, span, 0, 0, null, false, false, List.of(), null);
            RasterPoint a = point(from), b = point(to);
            MetricPoint ma = metric(a), mb = metric(b);
            double length = ma.distanceTo(mb);
            if (!context.productionEligible()
                    && context.eligibility() != OptionalEmissionContext.Eligibility.DECLARED_SYNTHETIC)
                return receipt(Status.UNAVAILABLE, Reason.SOURCE_INELIGIBLE, from, to, span, length, 0, null, false, false, List.of(), null);
            double pitch = minimumPitch();
            if (!(pitch > 0))
                return receipt(Status.UNAVAILABLE, Reason.NATIVE_PITCH_UNAVAILABLE, from, to, span, length, 0, null, false, false, List.of(), null);
            try (var evaluation = owner.child("connector-receipt")) {
                if (!context.routeSegmentAuthorized(a, b, budget, evaluation))
                    return receipt(Status.REJECTED, Reason.DECISION_AUTHORITY, from, to, span, length, pitch, null, false, false, List.of(), null);
                if (!context.evidenceSegmentAuthorized(a, b, budget, evaluation))
                    return receipt(Status.REJECTED, Reason.ACQUISITION_AUTHORITY, from, to, span, length, pitch, null, false, false, List.of(), null);
                var scalar = integral(a, b, evaluation);
                if (scalar.status() != BilinearSegmentIntegral.Status.COMPLETE)
                    return receipt(Status.UNAVAILABLE, Reason.SCALAR_SUPPORT, from, to, span, length, pitch, scalar, false, false, List.of(), null);
                Receipt transport = null;
                OrdinaryPhysicalLeg ordinaryTransport = null;
                MetricPoint directionStart = ma, directionEnd = mb;
                RasterPoint directionRasterStart = a, directionRasterEnd = b;
                if (length == 0) {
                    work(EmissionWorkBudget.Operation.TRANSPORT, 1);
                    if (incomingDirection != null) {
                        if (!issuedDirectionHere(incomingDirection) || incomingDirection.cursor() != from)
                            return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to,
                                span, length, pitch, scalar, false, false, List.of(), null);
                        transport = incomingDirection.optionalOrigin().orElse(null);
                        ordinaryTransport = incomingDirection.ordinaryOrigin().orElse(null);
                    } else if (incomingOrdinary != null) {
                        if (!issuedOrdinaryHere(incomingOrdinary) || incomingOrdinary.toEndpoint() != from)
                            return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to,
                                span, length, pitch, scalar, false, false, List.of(), null);
                        ordinaryTransport = incomingOrdinary;
                    } else {
                        if (incoming == null || incoming.status() != Status.ADMITTED || incoming.context() != context
                                || incoming.to() != from || !issuedHere(incoming))
                            return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to,
                                span, length, pitch, scalar, false, false, List.of(), null);
                        transport = incoming.emittedLengthMeters() > 0
                            ? incoming : incoming.transportedIncoming().orElse(null);
                        if (transport == null) ordinaryTransport = incoming.transportedOrdinary().orElse(null);
                        if (transport == null && (ordinaryTransport == null || !issuedOrdinaryHere(ordinaryTransport)))
                            return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to,
                                span, length, pitch, scalar, false, false, List.of(), null);
                    }
                    if (transport != null) {
                        if (!(transport.emittedLengthMeters() > 0))
                            return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to,
                                span, length, pitch, scalar, false, false, List.of(), null);
                        directionStart = metric(point(transport.from()));
                        directionEnd = metric(point(transport.to()));
                        directionRasterStart = point(transport.from());
                        directionRasterEnd = point(transport.to());
                    } else {
                        directionStart = ordinaryTransport.fromMetric();
                        directionEnd = ordinaryTransport.toMetric();
                        directionRasterStart = ordinaryTransport.fromRaster();
                        directionRasterEnd = ordinaryTransport.toRaster();
                    }
                } else if (incomingOrdinary != null || incomingDirection != null) {
                    return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to,
                        span, length, pitch, scalar, false, false, List.of(), null);
                }
                double headingLength = directionStart.distanceTo(directionEnd);
                MetricPoint normal = new MetricPoint(-(directionEnd.yMeters()-directionStart.yMeters())/headingLength,
                    (directionEnd.xMeters()-directionStart.xMeters())/headingLength);
                double probeLength = context.origin() == OptionalEmissionContext.Origin.MANAGED_CAPTURE
                    ? context.connectorProbeLengthUpper(a, b) : length;
                double[] ts = probeParameters(a, b, probeLength, pitch, evaluation);
                reserve(evaluation, AttemptMemoryLedger.referenceArrayBytes(ts.length) * 2);
                var probes = new ArrayList<Probe>(ts.length);
                if (queryPolicy == QueryPolicy.ZERO_GUARDED_LOCAL_V1) {
                    reserve(evaluation,Math.multiplyExact((long)ts.length,96));
                    var profileOwners=new IdentityHashMap<Probe,AttemptMemoryLedger.Owner>(ts.length);
                    for (double t : ts) {
                        RasterPoint probePoint = interpolate(a,b,t);
                        MetricPoint probeNormal = probeNormal(probePoint, directionRasterStart,
                            directionRasterEnd, normal);
                        Probe probe=measureLocalOrDense(t,probePoint,probeNormal,from,span,evaluation,profileOwners);
                        probes.add(probe);
                        if(executionPolicy==ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1) {
                            boolean immutableFinalCandidate=probe.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK
                                || probe.profileQuery().localNoModeProven();
                            if(immutableFinalCandidate) {
                                Reason failure=probeFailure(probe);
                                if(failure!=Reason.NONE) {
                                    transferProfileOwners(profileOwners,evaluation);
                                    var result=receipt(Status.REJECTED,failure,from,to,span,length,pitch,scalar,false,false,
                                        probes,transport,ordinaryTransport,ts.length,-1);
                                    return publish(result,evaluation);
                                }
                            }
                        }
                    }
                    // A changed middle profile invalidates both adjacent competitor proofs.
                    boolean changed;
                    do {
                        changed = false;
                        for (int i = 1; i < probes.size(); i++) {
                            work(EmissionWorkBudget.Operation.PREPARATION,1);
                            Probe left = probes.get(i - 1), right = probes.get(i);
                            if (!localPairSupported(left, right)) {
                                for (int side=0;side<2;side++) {
                                    work(EmissionWorkBudget.Operation.PREPARATION,1);
                                    int index=side==0?i-1:i;
                                    Probe old = probes.get(index);
                                    if (old.profileQuery().backend() == ProfileBackend.LOCAL) {
                                        probes.set(index, replaceWithDenseFallback(old,from,span,evaluation,
                                            profileOwners,FallbackCause.CROSS_PROBE_SUPPORT_UNAVAILABLE));
                                        changed = true;
                                    }
                                }
                            }
                        }
                    } while (changed);
                    transferProfileOwners(profileOwners,evaluation);
                } else {
                    for (double t : ts) {
                        RasterPoint probePoint = interpolate(a,b,t);
                        MetricPoint probeNormal = probeNormal(probePoint, directionRasterStart,
                            directionRasterEnd, normal);
                        Probe probe=measure(t,probePoint,probeNormal,from,span,evaluation);
                        probes.add(probe);
                        Reason reason=probeFailure(probe);
                        if(reason!=Reason.NONE) {
                            var result=receipt(Status.REJECTED,reason,from,to,span,length,pitch,scalar,false,false,
                                probes,transport,ordinaryTransport,ts.length,-1);
                            return publish(result,evaluation);
                        }
                        if(probes.size()>1 && !mutual(probes.get(probes.size()-2),probe)) {
                            var result=receipt(Status.REJECTED,Reason.NONUNIQUE_CONTINUATION,from,to,span,length,
                                pitch,scalar,false,false,probes,transport,ordinaryTransport,ts.length,-1);
                            return publish(result,evaluation);
                        }
                    }
                }
                if(queryPolicy==QueryPolicy.ZERO_GUARDED_LOCAL_V1) {
                    for(Probe probe:probes) {
                        Reason reason=probeFailure(probe);
                        if(reason!=Reason.NONE) {
                            var result=receipt(Status.REJECTED,reason,from,to,span,length,pitch,scalar,false,false,
                                probes,transport,ordinaryTransport,ts.length,-1);
                            return publish(result,evaluation);
                        }
                    }
                    for(int i=1;i<probes.size();i++)if(!mutual(probes.get(i-1),probes.get(i))) {
                        var result=receipt(Status.REJECTED,Reason.NONUNIQUE_CONTINUATION,from,to,span,length,
                            pitch,scalar,false,false,probes,transport,ordinaryTransport,ts.length,-1);
                        return publish(result,evaluation);
                    }
                }
                if (!endpointOwned(from, probes.get(0)) || !endpointOwned(to, probes.get(probes.size()-1))) {
                    var result=receipt(Status.UNAVAILABLE,Reason.ENDPOINT_OWNERSHIP,from,to,span,length,pitch,scalar,false,true,probes,transport,ordinaryTransport,ts.length,-1);
                    return publish(result,evaluation);
                }
                // Fresh GAP/censored endpoint ports are bound by the unique endpoint mode and its
                // direct mutually unique neighboring probe. Source provenance is left untouched.
                for (int k=0;k<Math.max(1,probes.size()-1);k++) {
                    work(EmissionWorkBudget.Operation.PREPARATION,1);
                    var left=probes.get(k); var right=probes.get(Math.min(k+1,probes.size()-1));
                    var slab=integral(left.rasterPosition(),right.rasterPosition(),evaluation);
                    double threshold=Math.max(left.threshold().shoulderThreshold(),right.threshold().shoulderThreshold());
                    if (slab.status()!=BilinearSegmentIntegral.Status.COMPLETE || slab.minimum().orElseThrow()<threshold) {
                        var result=receipt(Status.REJECTED,Reason.SCALAR_BELOW_ENVELOPE,from,to,span,length,pitch,scalar,false,true,probes,transport,ordinaryTransport,ts.length,k);
                        return publish(result,evaluation);
                    }
                }
                var result=receipt(Status.ADMITTED,Reason.NONE,from,to,span,length,pitch,scalar,true,true,probes,transport,ordinaryTransport,ts.length,-1);
                return publish(result,evaluation);
            }
        } catch (EmissionWorkBudget.LimitExceededException | AttemptMemoryLedger.ResourceLimitException
                | LocalScalarProfileExtractor.ResourceLimitException | CancellationException failure) {
            terminalFailure=failure;
            input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    // Issued receipt identity is necessary for zero-edge orientation. No scalar/probe result cache.
    private final IdentityHashMap<Receipt, Boolean> issued = new IdentityHashMap<>(0);
    private final IdentityHashMap<OrdinaryPhysicalLeg, Boolean> issuedOrdinary = new IdentityHashMap<>(0);
    private final IdentityHashMap<PhysicalDirectionCursor, Boolean> issuedDirections = new IdentityHashMap<>(0);
    /** Converts the precharged live evaluation payload into identities owned by issued receipts. */
    private Receipt publish(Receipt receipt,AttemptMemoryLedger.Owner evaluation) {
        for(Probe probe:receipt.probes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            retainPayload(evaluation,AttemptMemoryLedger.objectBytes(80),probe);
            retainPayload(evaluation,AttemptMemoryLedger.objectBytes(48),probe.profileQuery());
            retainPayload(evaluation,Math.addExact(AttemptMemoryLedger.objectBytes(24),
                AttemptMemoryLedger.referenceArrayBytes(probe.samples().size())),probe.samples());
        }
        if(!receipt.probes().isEmpty())
            retainPayload(evaluation,Math.addExact(AttemptMemoryLedger.objectBytes(24),
                AttemptMemoryLedger.referenceArrayBytes(receipt.probes().size())),receipt.probes());
        retainPayload(evaluation,AttemptMemoryLedger.objectBytes(160),receipt);
        evaluation.transferTo(owner);
        return remember(receipt);
    }
    private void retainPayload(AttemptMemoryLedger.Owner scope,long bytes,Object identity) {
        work(EmissionWorkBudget.Operation.ALLOCATION,1);
        scope.reserve(bytes).adopt(identity);
    }
    private Receipt remember(Receipt receipt) {
        reserve(owner, 256); work(EmissionWorkBudget.Operation.PREPARATION,1);
        issued.put(receipt,Boolean.TRUE); return receipt;
    }
    private boolean issuedHere(Receipt receipt) { work(EmissionWorkBudget.Operation.PAIR_LOOKUP,1); return issued.containsKey(receipt); }
    private boolean issuedOrdinaryHere(OrdinaryPhysicalLeg leg) {
        work(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        return issuedOrdinary.containsKey(leg);
    }
    private boolean issuedDirectionHere(PhysicalDirectionCursor direction) {
        work(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        return issuedDirections.containsKey(direction);
    }

    private void transferProfileOwners(IdentityHashMap<Probe,AttemptMemoryLedger.Owner> profileOwners,
            AttemptMemoryLedger.Owner evaluation) {
        for(var probeOwner:profileOwners.values()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            probeOwner.transferTo(evaluation);
            probeOwner.close();
        }
        profileOwners.clear();
    }

    private Receipt receipt(Status status,Reason reason,RowState from,RowState to,double span,double length,
            double pitch,BilinearSegmentIntegral.Result scalar,boolean envelope,boolean continuation,List<Probe> probes,Receipt incoming) {
        return receipt(status,reason,from,to,span,length,pitch,scalar,envelope,continuation,probes,incoming,-1,-1);
    }

    private Receipt receipt(Status status,Reason reason,RowState from,RowState to,double span,double length,
            double pitch,BilinearSegmentIntegral.Result scalar,boolean envelope,boolean continuation,List<Probe> probes,
            Receipt incoming,int plannedProbeCount,int failingSlabIndex) {
        return receipt(status,reason,from,to,span,length,pitch,scalar,envelope,continuation,probes,
            incoming,null,plannedProbeCount,failingSlabIndex);
    }

    private Receipt receipt(Status status,Reason reason,RowState from,RowState to,double span,double length,
            double pitch,BilinearSegmentIntegral.Result scalar,boolean envelope,boolean continuation,List<Probe> probes,
            Receipt incoming,OrdinaryPhysicalLeg ordinary,int plannedProbeCount,int failingSlabIndex) {
        work(EmissionWorkBudget.Operation.ALLOCATION,1);
        work(EmissionWorkBudget.Operation.PREPARATION,probes.size());
        String version=parameters.version()+"/"+queryPolicy.name();
        if(executionPolicy!=ExecutionPolicy.EAGER_ALL_PROBES_V1)version+="/"+executionPolicy.name();
        return new Receipt(status,reason,context,from,to,queryPolicy,version,span,length,pitch,
            Optional.ofNullable(scalar),envelope,continuation,probes,plannedProbeCount,failingSlabIndex,
            Optional.ofNullable(incoming),Optional.ofNullable(ordinary));
    }

    private Probe measure(double t,RasterPoint point,MetricPoint normal,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation) {
        double source=chainage(from.row())+span*t;
        work(EmissionWorkBudget.Operation.PREPARATION,context.resolution().spatialPitchSamples().size());
        double pitch=context.nativePitchAt(point,source);
        double requestedStep = context.lateralStepMeters();
        double outputPitch = outputPitch(point);
        double step = switch (queryPolicy) {
            case DENSE_EIGHTH_NATIVE_V1 -> Math.min(requestedStep, minimumPitch() / 8);
            case HALF_NATIVE_L0_V1 -> Math.min(requestedStep, Math.min(minimumPitch() / 2, outputPitch));
            case PIXEL_CAPPED_L0_V1 -> Math.min(requestedStep, Math.min(minimumPitch(), outputPitch));
            case ZERO_GUARDED_LOCAL_V1 -> Math.min(requestedStep, minimumPitch() / 8);
        };
        double halfWidth=context.searchHalfWidthMeters();
        int intervals=checkedCount(Math.ceil(2*halfWidth/step));
        int count=Math.addExact(intervals,1);
        reserve(evaluation,Math.addExact(512,Math.multiplyExact((long)count,88)));
        var samples=new ArrayList<Sample>(count);
        MetricPoint center=metric(point);
        for(int i=0;i<count;i++) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            double u=i==intervals?halfWidth:-halfWidth+2*halfWidth*i/intervals;
            var actual=new MetricPoint(center.xMeters()+u*normal.xMeters(),center.yMeters()+u*normal.yMeters());
            boolean authorized=context.routePositionAuthorized(actual,budget,evaluation)
                && context.evidencePositionAuthorized(actual,budget,evaluation);
            double value=authorized?strictValue(context.rasterAt(actual)):Double.NaN;
            var sampleReservation=retainedSampleReservation(evaluation);
            var sample=new Sample(u,value,Double.isFinite(value));
            sampleReservation.adopt(sample);
            samples.add(sample);
        }
        var extraction=extractor.extractDetailedWithAcceptedThresholds(samples,pitch,parameters.localization(),evaluation,
            CancellationProbe.NONE,n->work(EmissionWorkBudget.Operation.MODE_EXTRACTION,n));
        Mode selected=null; int compatible=0;
        for(Mode mode:extraction.result().baseline().modes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(mode.distanceToCenterSet(0)<=mode.localizationSigmaMeters()){selected=mode;compatible++;}
        }
        if(compatible!=1)selected=null;
        AcceptedModeThresholdReceipt threshold=null;
        for(var candidate:extraction.acceptedReceipts()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(candidate.mode()==selected)threshold=candidate;
        }
        work(EmissionWorkBudget.Operation.PREPARATION,samples.size());
        var evidence = new ProfileQueryEvidence(ProfileBackend.FULL_WINDOW, halfWidth,
            samples.size(), 0, FallbackCause.NONE, samples.size(), false);
        return new Probe(t,point,center,normal,pitch,samples,extraction,selected,threshold,evidence);
    }

    private Probe measureLocalOrDense(double t,RasterPoint point,MetricPoint normal,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation,IdentityHashMap<Probe,AttemptMemoryLedger.Owner> profileOwners) {
        double source=chainage(from.row())+span*t;
        double pitch=context.nativePitchAt(point,source);
        double delta=Math.min(context.lateralStepMeters(),Math.min(minimumPitch()/2,
            outputPitch(point)));
        if (!(delta>0) || !Double.isFinite(delta))
            return denseFallback(t,point,normal,from,span,evaluation,FallbackCause.INITIAL_WINDOW_TOO_WIDE);
        int initialHalfIntervals=checkedCount(Math.ceil(Math.max(pitch+6*delta,2.5*pitch+2*delta)/delta));
        double initialHalfWidth=initialHalfIntervals*delta;
        if (!Double.isFinite(initialHalfWidth) || initialHalfWidth>context.searchHalfWidthMeters())
            return denseFallback(t,point,normal,from,span,evaluation,FallbackCause.INITIAL_WINDOW_TOO_WIDE);
        int maximumHalfIntervals=checkedCount(Math.floor(context.searchHalfWidthMeters()/delta));
        var evidenceOwner=evaluation.child("connector-profile-evidence");
        try {
        if (initialHalfIntervals>maximumHalfIntervals) {
            Probe fallback=denseFallback(t,point,normal,from,span,evidenceOwner,FallbackCause.INITIAL_WINDOW_TOO_WIDE);
            profileOwners.put(fallback,evidenceOwner);
            return fallback;
        }
        FallbackCause fallback=FallbackCause.LOCAL_MODE_UNRESOLVED;
        int expansions=0;
        int totalSampleQueries=0;
        for(int halfIntervals=initialHalfIntervals;halfIntervals<=maximumHalfIntervals;halfIntervals++) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            double window=halfIntervals*delta;
            if(window>context.searchHalfWidthMeters())break;
            expansions=halfIntervals-initialHalfIntervals;
            var local=evidenceOwner.child("connector-local-probe");
            try {
                Probe probe=measureWindow(t,point,normal,from,span,local,window,delta,ProfileBackend.LOCAL,
                    expansions,FallbackCause.NONE,false,totalSampleQueries,halfIntervals);
                totalSampleQueries=Math.addExact(totalSampleQueries,probe.samples().size());
                boolean allZero=true;
                for(var sample:probe.samples()) {
                    work(EmissionWorkBudget.Operation.PREPARATION,1);
                    allZero &= sample.valid() && sample.intensity()==0.0;
                }
                if (allZero) {
                    Probe noMode=new Probe(t,point,probe.metricPosition(),normal,pitch,probe.samples(),probe.extraction(),
                        null,null,new ProfileQueryEvidence(ProfileBackend.LOCAL,window,probe.samples().size(),expansions,
                            FallbackCause.NONE,totalSampleQueries,true));
                    local.transferTo(evidenceOwner);
                    profileOwners.put(noMode,evidenceOwner);
                    return noMode;
                }
                if (localProof(probe) && probeFailure(probe)==Reason.NONE) {
                    local.transferTo(evidenceOwner);
                    profileOwners.put(probe,evidenceOwner);
                    return probe;
                }
                fallback=localFailureCause(probe);
            } finally {
                local.close();
            }
            if(fallback==FallbackCause.NOISE_FLOOR_NONZERO)break;
        }
        Probe dense=denseFallback(t,point,normal,from,span,evidenceOwner,fallback,expansions,totalSampleQueries);
        profileOwners.put(dense,evidenceOwner);
        return dense;
        } catch(RuntimeException failure) {
            evidenceOwner.close();
            throw failure;
        }
    }

    private Probe replaceWithDenseFallback(Probe old,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation,IdentityHashMap<Probe,AttemptMemoryLedger.Owner> profileOwners,
            FallbackCause cause) {
        var oldOwner=profileOwners.get(old);
        if(oldOwner==null)throw new IllegalStateException("Settled local profile has no evidence owner");
        var replacementOwner=evaluation.child("connector-profile-fallback");
        try {
            Probe replacement=denseFallback(old.parameter(),old.rasterPosition(),old.metricNormal(),from,span,
                replacementOwner,cause,old.profileQuery().expansionCount(),old.profileQuery().totalSampleQueries());
            profileOwners.put(replacement,replacementOwner);
            profileOwners.remove(old);
            // The caller still references old until probes.set returns. Nothing allocates
            // payload between this close and that replacement of the list element.
            oldOwner.close();
            return replacement;
        } catch(RuntimeException failure) {
            replacementOwner.close();
            throw failure;
        }
    }

    private FallbackCause localFailureCause(Probe probe) {
        if(probe.extraction().result().baseline().noiseFloor()!=0.0)return FallbackCause.NOISE_FLOOR_NONZERO;
        for(var sample:probe.samples()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(!sample.valid())return FallbackCause.ZERO_GUARD_UNAVAILABLE;
        }
        double pitch=probe.nativePitchMeters(); boolean left=false,right=false;
        for(var sample:probe.samples()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(sample.offsetMeters() < -3*pitch && sample.intensity()==0.0)left=true;
            if(sample.offsetMeters() > 3*pitch && sample.intensity()==0.0)right=true;
        }
        return probe.extraction().result().baseline().noiseFloor()!=0.0 ? FallbackCause.NOISE_FLOOR_NONZERO
            : left && right ? FallbackCause.LOCAL_MODE_UNRESOLVED : FallbackCause.ZERO_GUARD_UNAVAILABLE;
    }

    private Probe denseFallback(double t,RasterPoint point,MetricPoint normal,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation,FallbackCause cause) {
        return denseFallback(t,point,normal,from,span,evaluation,cause,0);
    }

    private Probe denseFallback(double t,RasterPoint point,MetricPoint normal,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation,FallbackCause cause,int expansions) {
        return denseFallback(t,point,normal,from,span,evaluation,cause,expansions,0);
    }

    private Probe denseFallback(double t,RasterPoint point,MetricPoint normal,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation,FallbackCause cause,int expansions,int previousQueries) {
        double step=Math.min(context.lateralStepMeters(),minimumPitch()/8);
        int intervals=checkedCount(Math.ceil(2*context.searchHalfWidthMeters()/step));
        double actualStep=2*context.searchHalfWidthMeters()/intervals;
        return measureWindow(t,point,normal,from,span,evaluation,context.searchHalfWidthMeters(),actualStep,
            ProfileBackend.DENSE_FALLBACK,expansions,cause,false,previousQueries,-1);
    }

    private Probe denseFallback(Probe old,RowState from,double span,AttemptMemoryLedger.Owner evaluation,
            FallbackCause cause) {
        return denseFallback(old.parameter(),old.rasterPosition(),old.metricNormal(),from,span,evaluation,cause,
            old.profileQuery().expansionCount(),old.profileQuery().totalSampleQueries());
    }

    private Probe measureWindow(double t,RasterPoint point,MetricPoint normal,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation,double halfWidth,double step,ProfileBackend backend,
            int expansions,FallbackCause cause,boolean localNoMode,int previousQueries,int centeredHalfIntervals) {
        double source=chainage(from.row())+span*t;
        double pitch=context.nativePitchAt(point,source);
        int intervals=centeredHalfIntervals>=0?Math.multiplyExact(2,centeredHalfIntervals)
            :checkedCount(Math.ceil(2*halfWidth/step));
        int count=Math.addExact(intervals,1);
        reserve(evaluation,Math.addExact(512,Math.multiplyExact((long)count,88)));
        var samples=new ArrayList<Sample>(count);
        MetricPoint center=metric(point);
        for(int i=0;i<count;i++) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            double u=centeredHalfIntervals>=0?centeredLatticeOffset(i,centeredHalfIntervals,step)
                :i==intervals?halfWidth:-halfWidth+2*halfWidth*i/intervals;
            var actual=new MetricPoint(center.xMeters()+u*normal.xMeters(),center.yMeters()+u*normal.yMeters());
            boolean authorized=context.routePositionAuthorized(actual,budget,evaluation)
                && context.evidencePositionAuthorized(actual,budget,evaluation);
            double value=authorized?strictValue(context.rasterAt(actual)):Double.NaN;
            var sampleReservation=retainedSampleReservation(evaluation);
            var sample=new Sample(u,value,Double.isFinite(value));
            sampleReservation.adopt(sample);
            samples.add(sample);
        }
        var extraction=extractor.extractDetailedWithAcceptedThresholds(samples,pitch,parameters.localization(),evaluation,
            CancellationProbe.NONE,n->work(EmissionWorkBudget.Operation.MODE_EXTRACTION,n));
        Mode selected=null; int compatible=0;
        for(Mode mode:extraction.result().baseline().modes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(mode.distanceToCenterSet(0)<=mode.localizationSigmaMeters()){selected=mode;compatible++;}
        }
        if(compatible!=1)selected=null;
        AcceptedModeThresholdReceipt threshold=null;
        for(var candidate:extraction.acceptedReceipts()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(candidate.mode()==selected)threshold=candidate;
        }
        work(EmissionWorkBudget.Operation.PREPARATION,samples.size());
        var evidence=new ProfileQueryEvidence(backend,halfWidth,samples.size(),expansions,cause,
            Math.addExact(previousQueries,samples.size()),localNoMode);
        return new Probe(t,point,center,normal,pitch,samples,extraction,selected,threshold,evidence);
    }

    private static double centeredLatticeOffset(int sampleIndex,int halfIntervals,double step) {
        int centeredIndex=Math.subtractExact(sampleIndex,halfIntervals);
        return centeredIndex==0?0.0:centeredIndex*step;
    }

    private boolean localProof(Probe probe) {
        double pitch=probe.nativePitchMeters();
        if(probe.extraction().result().baseline().noiseFloor()!=0.0 || probe.selectedMode()==null || probe.threshold()==null
                || probe.selectedMode().localizationSigmaMeters()>pitch/2
                || Math.abs(probe.threshold().originPeak().offsetMeters())>pitch
                || Math.abs(probe.threshold().originPeak().offsetMeters()-probe.selectedMode().centerMeters())>pitch/2) return false;
        boolean left=false,right=false;
        for(var sample:probe.samples()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(!sample.valid())return false;
            if(sample.offsetMeters() < -3*pitch && sample.intensity()==0.0)left=true;
            if(sample.offsetMeters() > 3*pitch && sample.intensity()==0.0)right=true;
        }
        if(!left || !right)return false;
        for(var peak:probe.extraction().result().rawPeaks()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(Math.abs(peak.offsetMeters())>3*pitch)continue;
            AcceptedModeThresholdReceipt accepted=null;
            for(var candidate:probe.extraction().acceptedReceipts()) {
                work(EmissionWorkBudget.Operation.PREPARATION,1);
                if(candidate.originPeak()==peak) { accepted=candidate; break; }
            }
            if(accepted==null || accepted.mode().localizationSigmaMeters()>pitch/2
                    || Math.abs(peak.offsetMeters()-accepted.mode().centerMeters())>pitch/2)return false;
            if(peak.firstSampleIndex()<6 || peak.lastSampleIndex()+6>=probe.samples().size())return false;
            for(int i=peak.firstSampleIndex()-6;i<=peak.lastSampleIndex()+6;i++) {
                work(EmissionWorkBudget.Operation.PREPARATION,1);
                if(!probe.samples().get(i).valid())return false;
            }
        }
        for(var suppressed:probe.extraction().result().suppressedModes())
            { work(EmissionWorkBudget.Operation.PREPARATION,1); if(Math.abs(suppressed.rawPeakOffsetMeters())<=3*pitch)return false; }
        for(var censored:probe.extraction().result().baseline().censoredModes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            double at=censored.inwardExtentMeasured()?censored.inwardSupportOffsetMeters():censored.boundaryOffsetMeters();
            if(Math.abs(at)<=3*pitch)return false;
        }
        return true;
    }

    private boolean localPairSupported(Probe a,Probe b) {
        if(a.profileQuery().localNoModeProven() || b.profileQuery().localNoModeProven())return true;
        if(a.profileQuery().backend()!=ProfileBackend.LOCAL && b.profileQuery().backend()!=ProfileBackend.LOCAL)return true;
        if(a.selectedMode()==null || b.selectedMode()==null)return false;
        double radius=1.5*Math.min(a.nativePitchMeters(),b.nativePitchMeters());
        return crossSupported(a,b,radius) && crossSupported(b,a,radius);
    }

    private boolean crossSupported(Probe target,Probe other,double radius) {
        if(target.profileQuery().backend()!=ProfileBackend.LOCAL)return true;
        double ox=other.metricPosition().xMeters()+other.metricNormal().xMeters()*other.selectedMode().centerMeters();
        double oy=other.metricPosition().yMeters()+other.metricNormal().yMeters()*other.selectedMode().centerMeters();
        double offset=(ox-target.metricPosition().xMeters())*target.metricNormal().xMeters()
            +(oy-target.metricPosition().yMeters())*target.metricNormal().yMeters();
        double p=target.nativePitchMeters();
        double lo=offset-radius-p/2,hi=offset+radius+p/2;
        if(lo < -3*p || hi > 3*p)return false;
        int first=indexAtOrAfter(target.samples(),lo),last=indexAtOrBefore(target.samples(),hi);
        if(first<0 || last<first)return false;
        for(int i=first;i<=last;i++) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(!target.samples().get(i).valid())return false;
        }
        for(var peak:target.extraction().result().rawPeaks()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(peak.offsetMeters()<lo || peak.offsetMeters()>hi)continue;
            AcceptedModeThresholdReceipt accepted=null;
            for(var candidate:target.extraction().acceptedReceipts()) {
                work(EmissionWorkBudget.Operation.PREPARATION,1);
                if(candidate.originPeak()==peak) { accepted=candidate; break; }
            }
            if(accepted==null || accepted.mode().localizationSigmaMeters()>p/2
                    || Math.abs(peak.offsetMeters()-accepted.mode().centerMeters())>p/2)return false;
            if(peak.firstSampleIndex()<6 || peak.lastSampleIndex()+6>=target.samples().size())return false;
            for(int i=peak.firstSampleIndex()-6;i<=peak.lastSampleIndex()+6;i++) {
                work(EmissionWorkBudget.Operation.PREPARATION,1);
                if(!target.samples().get(i).valid())return false;
            }
        }
        for(var suppressed:target.extraction().result().suppressedModes())
            { work(EmissionWorkBudget.Operation.PREPARATION,1); if(suppressed.rawPeakOffsetMeters()>=lo && suppressed.rawPeakOffsetMeters()<=hi)return false; }
        for(var censored:target.extraction().result().baseline().censoredModes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            double at=censored.inwardExtentMeasured()?censored.inwardSupportOffsetMeters():censored.boundaryOffsetMeters();
            if(at>=lo && at<=hi)return false;
        }
        return true;
    }

    private int indexAtOrAfter(List<Sample> samples,double offset) {
        for(int i=0;i<samples.size();i++) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(samples.get(i).offsetMeters()>=offset)return i;
        }
        return samples.size();
    }
    private int indexAtOrBefore(List<Sample> samples,double offset) {
        for(int i=samples.size()-1;i>=0;i--) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(samples.get(i).offsetMeters()<=offset)return i;
        }
        return -1;
    }

    private Reason probeFailure(Probe probe) {
        var detailed=probe.extraction().result(); int compatible=0;
        for(Mode mode:detailed.baseline().modes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(mode.distanceToCenterSet(0)<=mode.localizationSigmaMeters())compatible++;
        }
        if(compatible==0)return probe.profileQuery().localNoModeProven()
            ? Reason.LOCAL_NO_MODE : Reason.NO_COMPLETE_MODE;
        if(compatible!=1)return Reason.AMBIGUOUS_MODE;
        var selected=probe.selectedMode();
        int flanks=0,cores=0;
        for(var flank:detailed.flankReceipts()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(flank.mode()==selected && flank.hasTwoSidedValidOutwardContinuation())flanks++;
        }
        for(var core:detailed.rawCoreReceipts()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(core.mode()==selected && core.leftAdjacentObservedValid() && core.leftAdjacentBelowCore()
                    && core.rightAdjacentObservedValid() && core.rightAdjacentBelowCore())cores++;
        }
        if(flanks!=1 || cores!=1)return Reason.INCOMPLETE_FLANKS;
        if(probe.threshold()==null)return Reason.MISSING_THRESHOLD;
        double radius=1.5*probe.nativePitchMeters();
        for(var suppressed:detailed.suppressedModes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(suppressed.mode().distanceToCenterSet(selected.centerMeters())<=radius)
                return Reason.CENSORED_OR_SUPPRESSED_COMPETITOR;
        }
        for(var censored:detailed.baseline().censoredModes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            double edge=censored.inwardExtentMeasured()?censored.inwardSupportOffsetMeters():censored.boundaryOffsetMeters();
            if(Math.abs(edge-selected.centerMeters())<=radius)return Reason.CENSORED_OR_SUPPRESSED_COMPETITOR;
        }
        return Reason.NONE;
    }

    private boolean mutual(Probe a,Probe b) {
        double maximum=1.5*Math.min(a.nativePitchMeters(),b.nativePitchMeters());
        int forward=0,backward=0;
        for(Mode mode:b.extraction().result().baseline().modes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(Math.abs(mode.centerMeters()-a.selectedMode().centerMeters())<=maximum)forward++;
        }
        for(Mode mode:a.extraction().result().baseline().modes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(Math.abs(mode.centerMeters()-b.selectedMode().centerMeters())<=maximum)backward++;
        }
        return forward==1 && backward==1 && Math.abs(a.selectedMode().centerMeters()-b.selectedMode().centerMeters())<=maximum;
    }

    private boolean endpointOwned(RowState state,Probe probe) {
        if(state.sourceClass()!=SourceClass.OBSERVED)return true; // Fresh measured complete port, never an old position.
        var peak=state.peakOwner(); if(peak==null || peak.syntheticCenter())return false;
        var observation=context.observations().get(state.row());
        RasterPoint anchor=observation.location().rasterPoint(), normal=observation.normal();
        MetricPoint peakPoint=metric(new RasterPoint(anchor.x()+normal.x()*peak.offsetPx(),anchor.y()+normal.y()*peak.offsetPx()));
        double offset=(peakPoint.xMeters()-probe.metricPosition().xMeters())*probe.metricNormal().xMeters()
            +(peakPoint.yMeters()-probe.metricPosition().yMeters())*probe.metricNormal().yMeters();
        double uncertainty=Math.max(0,peak.centerUncertaintyPx())*metricNormalPitch(state.row());
        return probe.selectedMode().distanceToCenterSet(offset)<=probe.selectedMode().localizationSigmaMeters()+uncertainty
            && peakPoint.distanceTo(probe.metricPosition())<=1.5*probe.nativePitchMeters()+uncertainty;
    }

    private boolean validState(RowState state) {
        double physicalPitch = metricNormalPitch(state.row());
        if (!Double.isFinite(physicalPitch) || physicalPitch <= 0
                || Math.abs(state.offsetRasterPixels()) > context.searchHalfWidthMeters() / physicalPitch) return false;
        if(state.sourceClass()!=SourceClass.OBSERVED)return state.peakOwner()==null;
        for(var peak:input.profiles().levelZeroProfiles().get(state.row()).peaks()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(peak==state.peakOwner())return !peak.syntheticCenter();
        }
        return false;
    }

    /** Captured displacement of one offset unit on this row's frozen raster normal. */
    private double metricNormalPitch(int row) {
        work(EmissionWorkBudget.Operation.PREPARATION, 1);
        return context.metricNormalPitchAtRow(row);
    }

    private int barrierIndex(Barrier target) {
        for (int index = 0; index < barriers.size(); index++) {
            work(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (barriers.get(index) == target) return index;
        }
        return -1;
    }

    private boolean ordinaryAdjacent(PhysicalEndpoint from, PhysicalEndpoint to) {
        if (from instanceof RowState left && to instanceof RowState right) {
            return states.containsKey(left) && states.containsKey(right)
                && originalOrdinal(right.row()) == originalOrdinal(left.row()) + 1
                && chainage(right.row()) >= chainage(left.row())
                && !barrierBetween(left.row(), right.row());
        }
        if (from instanceof RowState left && to instanceof Barrier right) {
            int rightIndex = barrierIndex(right);
            if (!states.containsKey(left) || rightIndex < 0 || right.leftRow() != left.row()) return false;
            for (int index = 0; index < rightIndex; index++) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (barriers.get(index).leftRow() == left.row()) return false;
            }
            return true;
        }
        if (from instanceof Barrier left && to instanceof RowState right) {
            int leftIndex = barrierIndex(left);
            if (!states.containsKey(right) || leftIndex < 0 || left.rightRow() != right.row()) return false;
            for (int index = leftIndex + 1; index < barriers.size(); index++) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (barriers.get(index).leftRow() == left.leftRow()) return false;
            }
            return true;
        }
        if (from instanceof Barrier left && to instanceof Barrier right) {
            int leftIndex = barrierIndex(left), rightIndex = barrierIndex(right);
            return leftIndex >= 0 && rightIndex == leftIndex + 1
                && left.leftRow() == right.leftRow() && left.rightRow() == right.rightRow();
        }
        return false;
    }

    private RasterPoint endpointRaster(PhysicalEndpoint endpoint) {
        return endpoint instanceof RowState row ? point(row)
            : context.rasterAt(((Barrier) endpoint).exactPosition());
    }

    private MetricPoint endpointMetric(PhysicalEndpoint endpoint) {
        return endpoint instanceof RowState row ? metric(point(row))
            : ((Barrier) endpoint).exactPosition();
    }

    private boolean barrierBetween(int from,int to) {
        for(int row:mandatoryRows){work(EmissionWorkBudget.Operation.PREPARATION,1);if(row>from && row<to)return true;}
        for(var barrier:barriers){work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(barrier.leftRow()>from && barrier.leftRow()<to || barrier.rightRow()>from && barrier.rightRow()<to
                    || from<=barrier.leftRow() && to>=barrier.rightRow())return true;}
        return false;
    }

    private double minimumPitch() {
        if(context.origin()==OptionalEmissionContext.Origin.MANAGED_CAPTURE)
            return context.minimumNativePitchMeters().orElse(Double.NaN);
        if(context.resolution().nativePitchMeters().isEmpty())return Double.NaN;
        double minimum=context.resolution().nativePitchMeters().getAsDouble();
        for(var sample:context.resolution().spatialPitchSamples()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(sample.nativePitchMeters().isEmpty())return Double.NaN;
            minimum=Math.min(minimum,sample.nativePitchMeters().getAsDouble());
        }
        return minimum;
    }
    private double chainage(int row){return context.sourceChainageMeters(row);}
    private int originalOrdinal(int row) {
        return originalOrdinalByContextRow.isEmpty() ? row : originalOrdinalByContextRow.get(row);
    }
    private RasterPoint point(RowState state) {
        var fact=context.observations().get(state.row());var p=fact.location().rasterPoint();var n=fact.normal();
        return new RasterPoint(p.x()+n.x()*state.offsetRasterPixels(),p.y()+n.y()*state.offsetRasterPixels());
    }
    private MetricPoint metric(RasterPoint point){work(EmissionWorkBudget.Operation.PREPARATION,1);return context.metricAt(point);}
    private static RasterPoint interpolate(RasterPoint a,RasterPoint b,double t){return new RasterPoint(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t);}
    private MetricPoint probeNormal(RasterPoint probe, RasterPoint from, RasterPoint to,
            MetricPoint detachedNormal) {
        if (context.origin() != OptionalEmissionContext.Origin.MANAGED_CAPTURE) return detachedNormal;
        work(EmissionWorkBudget.Operation.PREPARATION, 1);
        RasterPoint normal = context.normalAt(probe, from, to);
        return new MetricPoint(normal.x(), normal.y());
    }

    private double outputPitch(RasterPoint point) {
        return context.origin() == OptionalEmissionContext.Origin.MANAGED_CAPTURE
            ? context.outputPitchAt(metric(point)) : context.outputRasterPitchMeters();
    }

    private double[] probeParameters(RasterPoint a,RasterPoint b,double length,double pitch,AttemptMemoryLedger.Owner evaluation) {
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.NativeScalarGridView grid=context.nativeGrid();var ga=grid.toGrid(a);var gb=grid.toGrid(b);
        double dx=gb.x()-ga.x(),dy=gb.y()-ga.y();
        int steps=Math.max(1,checkedCount(Math.ceil(length/(.5*pitch))));
        long capacity=Math.addExact(4L+steps,Math.addExact(checkedCount(Math.ceil(Math.abs(dx))),checkedCount(Math.ceil(Math.abs(dy)))));
        int count=checkedCount(capacity);
        reserve(evaluation,AttemptMemoryLedger.arrayBytes(count,8)*2);
        var values=new double[count];int used=0;
        values[used++]=0;values[used++]=1;
        for(int i=1;i<steps;i++){work(EmissionWorkBudget.Operation.PREPARATION,1);values[used++]=(double)i/steps;}
        if(dx!=0)for(double x=Math.floor(Math.min(ga.x(),gb.x()))+1;x<Math.max(ga.x(),gb.x());x++) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);values[used++]=(x-ga.x())/dx;
        }
        if(dy!=0)for(double y=Math.floor(Math.min(ga.y(),gb.y()))+1;y<Math.max(ga.y(),gb.y());y++) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);values[used++]=(y-ga.y())/dy;
        }
        work(EmissionWorkBudget.Operation.PREPARATION,Math.multiplyExact((long)used,Math.max(1,64-Long.numberOfLeadingZeros(used))));
        Arrays.sort(values,0,used);int unique=1;
        for(int i=1;i<used;i++){work(EmissionWorkBudget.Operation.PREPARATION,1);if(values[i]!=values[unique-1])values[unique++]=values[i];}
        work(EmissionWorkBudget.Operation.ALLOCATION,1);work(EmissionWorkBudget.Operation.PREPARATION,unique);
        return Arrays.copyOf(values,unique);
    }

    private BilinearSegmentIntegral.Result integral(RasterPoint a,RasterPoint b,AttemptMemoryLedger.Owner evaluation) {
        var grid=context.nativeGrid();var ga=grid.toGrid(a);var gb=grid.toGrid(b);
        long bound=checkedCount(Math.ceil(Math.abs(gb.x()-ga.x())+Math.abs(gb.y()-ga.y()))+16);
        work(EmissionWorkBudget.Operation.ALLOCATION,1);
        try(var scratch=evaluation.reserve(Math.addExact(4096,Math.multiplyExact(bound,2048)))) {
            return BilinearSegmentIntegral.evaluate(grid,ga,gb,CancellationProbe.NONE,
                ()->work(EmissionWorkBudget.Operation.CELL_QUERY,1));
        }
    }

    /** Strict point query checks every touched interpolation cell, including grid-line/corner neighbors. */
    private double strictValue(RasterPoint base) {
        var grid=context.nativeGrid();var p=grid.toGrid(base);
        double x=p.x(),y=p.y();int w=grid.width(),h=grid.height();
        work(EmissionWorkBudget.Operation.PREPARATION,1);
        if(x<0 || y<0 || x>w-1.0 || y>h-1.0)return Double.NaN;
        int cellX=Math.min(w-2,(int)Math.floor(x)),cellY=Math.min(h-2,(int)Math.floor(y));
        double nearX=Math.rint(x),nearY=Math.rint(y);
        boolean lineX=Math.abs(x-nearX)<=Math.max(1e-12,4*Math.ulp(x));
        boolean lineY=Math.abs(y-nearY)<=Math.max(1e-12,4*Math.ulp(y));
        int firstX=lineX?Math.max(0,(int)nearX-1):cellX,lastX=lineX?Math.min(w-2,(int)nearX):cellX;
        int firstY=lineY?Math.max(0,(int)nearY-1):cellY,lastY=lineY?Math.min(h-2,(int)nearY):cellY;
        for(int cy=firstY;cy<=lastY;cy++)for(int cx=firstX;cx<=lastX;cx++) {
            work(EmissionWorkBudget.Operation.CELL_QUERY,1);if(!grid.supportsCell(cx,cy))return Double.NaN;
            for(int oy=0;oy<=1;oy++)for(int ox=0;ox<=1;ox++) {
                work(EmissionWorkBudget.Operation.NODE_READ,1);
                double value=grid.nodeValue(cx+ox,cy+oy);
                if(!Double.isFinite(value)||value<0||value>1)return Double.NaN;
            }
        }
        work(EmissionWorkBudget.Operation.NODE_READ,4);
        double v00=grid.nodeValue(cellX,cellY),v10=grid.nodeValue(cellX+1,cellY),v01=grid.nodeValue(cellX,cellY+1),v11=grid.nodeValue(cellX+1,cellY+1);
        double tx=x-cellX,ty=y-cellY;
        return Math.max(0,Math.min(1,(v00*(1-tx)+v10*tx)*(1-ty)+(v01*(1-tx)+v11*tx)*ty));
    }

    private int checkedCount(double count) {
        if(!Double.isFinite(count)||count<0||count>Integer.MAX_VALUE-8L) {
            // Force the same sticky bounded refusal before any impossible allocation/loop.
            work(EmissionWorkBudget.Operation.ALLOCATION,Long.MAX_VALUE);
            throw new AssertionError("Work cap must refuse impossible connector size");
        }
        return (int)count;
    }
    private void reserve(AttemptMemoryLedger.Owner scope,long bytes){work(EmissionWorkBudget.Operation.ALLOCATION,1);scope.reserve(bytes);}
    private AttemptMemoryLedger.Reservation retainedSampleReservation(AttemptMemoryLedger.Owner scope) {
        work(EmissionWorkBudget.Operation.ALLOCATION,1);
        return scope.reserve(AttemptMemoryLedger.objectBytes(17));
    }
    private void work(EmissionWorkBudget.Operation operation,long units){budget.charge(operation,units);}
    private void requireLive(){if(terminalFailure!=null)throw terminalFailure;if(owner.isClosed())throw new IllegalStateException("Connector session is closed");}
    @Override public void close(){owner.close();}
}
