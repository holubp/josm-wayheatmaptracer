package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ScalarFieldGridView;
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
    public enum QueryPolicy { DENSE_EIGHTH_NATIVE_V1, HALF_NATIVE_L0_V1, PIXEL_CAPPED_L0_V1 }
    public enum SourceClass { OBSERVED, GAP, CENSORED }
    public enum Status { ADMITTED, REJECTED, UNAVAILABLE }
    public enum Reason {
        NONE, FOREIGN_STATE, INVALID_STATE, SPAN, MANDATORY_BARRIER, NATIVE_PITCH_UNAVAILABLE,
        SOURCE_INELIGIBLE, DECISION_AUTHORITY, ACQUISITION_AUTHORITY, SCALAR_SUPPORT,
        NO_COMPLETE_MODE, AMBIGUOUS_MODE, INCOMPLETE_FLANKS, MISSING_THRESHOLD,
        CENSORED_OR_SUPPRESSED_COMPETITOR, NONUNIQUE_CONTINUATION, ENDPOINT_OWNERSHIP,
        SCALAR_BELOW_ENVELOPE, INCOMING_DIRECTION_UNAVAILABLE
    }

    /** Exact native enumeration identity; peak owns the underlying band, not necessarily offset. */
    public record RowState(int row, int nativeStateId, double offsetRasterPixels,
            SourceClass sourceClass, CrossSectionPeak peakOwner) {
        public RowState {
            if (row < 0 || nativeStateId < 0 || !Double.isFinite(offsetRasterPixels) || sourceClass == null)
                throw new IllegalArgumentException("Native row/state identity is incomplete");
        }
    }

    /** Exact protected event with its two immediately bracketing mandatory source rows. */
    public record Barrier(String occurrenceId, int leftRow, int rightRow, double sourceChainageMeters,
            MetricPoint exactPosition) {
        public Barrier {
            if (occurrenceId == null || occurrenceId.isBlank() || leftRow < 0 || rightRow != leftRow + 1
                    || !Double.isFinite(sourceChainageMeters) || exactPosition == null)
                throw new IllegalArgumentException("Protected connector barrier is incomplete");
        }
    }

    /**
     * Immutable caller-owned inventory declaration. Later engine adapters must populate all actual
     * states/events from their native constructors. This declaration is not engine-authority attestation.
     * Caller owns construction; the evaluator reserves its execution copy before allocating it.
     */
    public record Domain(List<RowState> states, List<Integer> mandatoryRows, List<Barrier> barriers) {
        public Domain(List<RowState> states, List<Integer> mandatoryRows) {
            this(states, mandatoryRows, List.of());
        }
        public Domain {
            states = List.copyOf(states);
            mandatoryRows = List.copyOf(mandatoryRows);
            barriers = List.copyOf(barriers);
        }
    }

    /** One actual transverse query; all accepted, censored and suppressed alternatives are retained. */
    public record Probe(double parameter, RasterPoint rasterPosition, MetricPoint metricPosition,
            MetricPoint metricNormal, double nativePitchMeters, List<Sample> samples,
            AcceptedThresholdExtraction extraction, Mode selectedMode,
            AcceptedModeThresholdReceipt threshold) {
        public Probe { samples = List.copyOf(samples); }
    }

    /** Scalar validity and measured native continuation have separate explicit success fields. */
    public record Receipt(Status status, Reason reason, OptionalEmissionContext context,
            RowState from, RowState to, QueryPolicy queryPolicy, String parameterVersion,
            double sourceSpanMeters, double emittedLengthMeters, double minimumNativePitchMeters,
            Optional<BilinearSegmentIntegral.Result> scalar, boolean scalarEnvelopeComplete,
            boolean measuredNativeContinuationComplete, List<Probe> probes,
            Optional<Receipt> transportedIncoming) {
        public Receipt {
            Objects.requireNonNull(status); Objects.requireNonNull(reason); Objects.requireNonNull(context);
            Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(queryPolicy);
            Objects.requireNonNull(parameterVersion);
            scalar = Objects.requireNonNull(scalar); transportedIncoming = Objects.requireNonNull(transportedIncoming);
            probes = List.copyOf(probes);
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
    private final AttemptMemoryLedger.Owner owner;
    private final QueryPolicy queryPolicy;
    private final IdentityHashMap<RowState, Boolean> states;
    private final List<Integer> mandatoryRows;
    private final List<Barrier> barriers;
    private final EvidenceModelParameters parameters = EvidenceModelParameters.defaults();
    private final LocalScalarProfileExtractor extractor = new LocalScalarProfileExtractor();
    private RuntimeException terminalFailure;

    public NativeConnectorEvaluator(SampledEmissionInput input, Domain domain) {
        this(input, domain, QueryPolicy.DENSE_EIGHTH_NATIVE_V1);
    }

    public NativeConnectorEvaluator(SampledEmissionInput input, Domain domain, QueryPolicy queryPolicy) {
        this.input = Objects.requireNonNull(input);
        Objects.requireNonNull(domain);
        this.queryPolicy = Objects.requireNonNull(queryPolicy, "queryPolicy");
        owner = input.openExecutionOwner("native-connector");
        context = input.context(); budget = input.workBudget();
        try {
            // IdentityHashMap explicit sizing: at most 8 references per declaration is conservative.
            reserve(owner, Math.addExact(2048L, Math.multiplyExact(160L, domain.states().size()))
                + Math.multiplyExact(80L, (long) domain.mandatoryRows().size() + domain.barriers().size()));
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
            for (int index = 1; index < nativeIds.length; index++) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (nativeIds[index] == nativeIds[index - 1])
                    throw new IllegalArgumentException("Duplicate native row/state identity");
            }
            mandatoryRows = List.copyOf(domain.mandatoryRows()); barriers = List.copyOf(domain.barriers());
            for (int row : mandatoryRows) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (row < 0 || row >= context.observations().size())
                    throw new IllegalArgumentException("Mandatory row is outside the source");
            }
            for (Barrier barrier : barriers) {
                work(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (barrier.rightRow() >= context.observations().size()
                        || chainage(barrier.leftRow()) > barrier.sourceChainageMeters()
                        || chainage(barrier.rightRow()) < barrier.sourceChainageMeters())
                    throw new IllegalArgumentException("Protected event does not match its source bracket");
            }
        } catch (RuntimeException failure) {
            owner.close();
            if (failure instanceof EmissionWorkBudget.LimitExceededException
                    || failure instanceof AttemptMemoryLedger.ResourceLimitException
                    || failure instanceof CancellationException) input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    public Receipt evaluate(RowState from, RowState to) { return evaluate(from, to, null); }

    /** For a zero edge, incoming must be the preceding same-session receipt, including zero transport. */
    public Receipt evaluate(RowState from, RowState to, Receipt incoming) {
        requireLive();
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        try {
            input.requireOpenAndUnchanged();
            work(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
            reserve(owner, 384); // A bounded result shell is charged even on early refusal.
            if (!states.containsKey(from) || !states.containsKey(to))
                return receipt(Status.UNAVAILABLE, Reason.FOREIGN_STATE, from, to, 0, 0, 0, null, false, false, List.of(), null);
            double span = chainage(to.row()) - chainage(from.row());
            if (to.row() <= from.row() || to.row() - from.row() - 1 > 16 || span > 20 || span < 0)
                return receipt(Status.REJECTED, Reason.SPAN, from, to, span, 0, 0, null, false, false, List.of(), null);
            if (barrierBetween(from.row(), to.row()))
                return receipt(Status.REJECTED, Reason.MANDATORY_BARRIER, from, to, span, 0, 0, null, false, false, List.of(), null);
            if (!validState(from) || !validState(to))
                return receipt(Status.UNAVAILABLE, Reason.INVALID_STATE, from, to, span, 0, 0, null, false, false, List.of(), null);
            RasterPoint a = point(from), b = point(to);
            MetricPoint ma = metric(a), mb = metric(b);
            double length = ma.distanceTo(mb);
            if (context.eligibility() != OptionalEmissionContext.Eligibility.FACTUAL_DETACHED
                    && context.eligibility() != OptionalEmissionContext.Eligibility.DECLARED_SYNTHETIC)
                return receipt(Status.UNAVAILABLE, Reason.SOURCE_INELIGIBLE, from, to, span, length, 0, null, false, false, List.of(), null);
            double pitch = minimumPitch();
            if (!(pitch > 0))
                return receipt(Status.UNAVAILABLE, Reason.NATIVE_PITCH_UNAVAILABLE, from, to, span, length, 0, null, false, false, List.of(), null);
            try (var evaluation = owner.child("connector-receipt")) {
                if (!authority(context.decisionRegion(), ma, mb, evaluation))
                    return receipt(Status.REJECTED, Reason.DECISION_AUTHORITY, from, to, span, length, pitch, null, false, false, List.of(), null);
                if (!authority(context.evidenceRegion(), ma, mb, evaluation))
                    return receipt(Status.REJECTED, Reason.ACQUISITION_AUTHORITY, from, to, span, length, pitch, null, false, false, List.of(), null);
                var scalar = integral(a, b, evaluation);
                if (scalar.status() != BilinearSegmentIntegral.Status.COMPLETE)
                    return receipt(Status.UNAVAILABLE, Reason.SCALAR_SUPPORT, from, to, span, length, pitch, scalar, false, false, List.of(), null);
                Receipt transport = null;
                MetricPoint directionStart = ma, directionEnd = mb;
                if (length == 0) {
                    work(EmissionWorkBudget.Operation.TRANSPORT, 1);
                    if (incoming == null || incoming.status() != Status.ADMITTED || incoming.context() != context
                            || incoming.to() != from || !issuedHere(incoming))
                        return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to, span, length, pitch, scalar, false, false, List.of(), null);
                    transport = incoming.emittedLengthMeters() > 0 ? incoming : incoming.transportedIncoming().orElse(null);
                    if (transport == null || transport.emittedLengthMeters() <= 0)
                        return receipt(Status.UNAVAILABLE, Reason.INCOMING_DIRECTION_UNAVAILABLE, from, to, span, length, pitch, scalar, false, false, List.of(), null);
                    directionStart = metric(point(transport.from())); directionEnd = metric(point(transport.to()));
                }
                double headingLength = directionStart.distanceTo(directionEnd);
                MetricPoint normal = new MetricPoint(-(directionEnd.yMeters()-directionStart.yMeters())/headingLength,
                    (directionEnd.xMeters()-directionStart.xMeters())/headingLength);
                double[] ts = probeParameters(a, b, length, pitch, evaluation);
                reserve(evaluation, AttemptMemoryLedger.referenceArrayBytes(ts.length) * 2);
                var probes = new ArrayList<Probe>(ts.length);
                for (double t : ts) {
                    var probe = measure(t, interpolate(a,b,t), normal, from, span, evaluation);
                    probes.add(probe);
                    Reason reason = probeFailure(probe);
                    if (reason != Reason.NONE) {
                        var result=receipt(Status.REJECTED, reason, from,to,span,length,pitch,scalar,false,false,probes,transport);
                        evaluation.transferTo(owner); return remember(result);
                    }
                    if (probes.size() > 1 && !mutual(probes.get(probes.size()-2), probe)) {
                        var result=receipt(Status.REJECTED,Reason.NONUNIQUE_CONTINUATION,from,to,span,length,pitch,scalar,false,false,probes,transport);
                        evaluation.transferTo(owner); return remember(result);
                    }
                }
                if (from.sourceClass() != SourceClass.OBSERVED && to.sourceClass() != SourceClass.OBSERVED
                        || !endpointOwned(from, probes.get(0)) || !endpointOwned(to, probes.get(probes.size()-1))) {
                    var result=receipt(Status.UNAVAILABLE,Reason.ENDPOINT_OWNERSHIP,from,to,span,length,pitch,scalar,false,true,probes,transport);
                    evaluation.transferTo(owner); return remember(result);
                }
                // Fresh GAP/censored endpoint ports are bound by the unique endpoint mode and its
                // direct mutually unique neighboring probe. Source provenance is left untouched.
                for (int k=0;k<Math.max(1,probes.size()-1);k++) {
                    work(EmissionWorkBudget.Operation.PREPARATION,1);
                    var left=probes.get(k); var right=probes.get(Math.min(k+1,probes.size()-1));
                    var slab=integral(left.rasterPosition(),right.rasterPosition(),evaluation);
                    double threshold=Math.max(left.threshold().shoulderThreshold(),right.threshold().shoulderThreshold());
                    if (slab.status()!=BilinearSegmentIntegral.Status.COMPLETE || slab.minimum().orElseThrow()<threshold) {
                        var result=receipt(Status.REJECTED,Reason.SCALAR_BELOW_ENVELOPE,from,to,span,length,pitch,scalar,false,true,probes,transport);
                        evaluation.transferTo(owner); return remember(result);
                    }
                }
                var result=receipt(Status.ADMITTED,Reason.NONE,from,to,span,length,pitch,scalar,true,true,probes,transport);
                evaluation.transferTo(owner); return remember(result);
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
    private Receipt remember(Receipt receipt) {
        reserve(owner, 256); work(EmissionWorkBudget.Operation.PREPARATION,1);
        issued.put(receipt,Boolean.TRUE); return receipt;
    }
    private boolean issuedHere(Receipt receipt) { work(EmissionWorkBudget.Operation.PAIR_LOOKUP,1); return issued.containsKey(receipt); }

    private Receipt receipt(Status status,Reason reason,RowState from,RowState to,double span,double length,
            double pitch,BilinearSegmentIntegral.Result scalar,boolean envelope,boolean continuation,List<Probe> probes,Receipt incoming) {
        work(EmissionWorkBudget.Operation.ALLOCATION,1);
        work(EmissionWorkBudget.Operation.PREPARATION,probes.size());
        return new Receipt(status,reason,context,from,to,queryPolicy,parameters.version() + "/" + queryPolicy.name(),span,length,pitch,
            Optional.ofNullable(scalar),envelope,continuation,probes,Optional.ofNullable(incoming));
    }

    private Probe measure(double t,RasterPoint point,MetricPoint normal,RowState from,double span,
            AttemptMemoryLedger.Owner evaluation) {
        double source=chainage(from.row())+span*t+context.profileChainage().sourceOriginGroundMeters();
        work(EmissionWorkBudget.Operation.PREPARATION,context.resolution().spatialPitchSamples().size());
        double pitch=context.resolution().effectivePitchMetersAt(source);
        double requestedStep = context.lateralStepMeters();
        double outputPitch = context.resolution().outputRasterPitchMeters();
        double step = switch (queryPolicy) {
            case DENSE_EIGHTH_NATIVE_V1 -> Math.min(requestedStep, minimumPitch() / 8);
            case HALF_NATIVE_L0_V1 -> Math.min(requestedStep, Math.min(minimumPitch() / 2, outputPitch));
            case PIXEL_CAPPED_L0_V1 -> Math.min(requestedStep, Math.min(minimumPitch(), outputPitch));
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
            boolean authorized=context.decisionRegion().containsConservatively(actual,CancellationProbe.NONE,n->work(EmissionWorkBudget.Operation.REGION_QUERY,n))
                && context.evidenceRegion().containsConservatively(actual,CancellationProbe.NONE,n->work(EmissionWorkBudget.Operation.REGION_QUERY,n));
            double value=authorized?strictValue(context.rasterTransform().metricToPixelCenter(actual)):Double.NaN;
            samples.add(new Sample(u,value,Double.isFinite(value)));
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
        return new Probe(t,point,center,normal,pitch,samples,extraction,selected,threshold);
    }

    private Reason probeFailure(Probe probe) {
        var detailed=probe.extraction().result(); int compatible=0;
        for(Mode mode:detailed.baseline().modes()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(mode.distanceToCenterSet(0)<=mode.localizationSigmaMeters())compatible++;
        }
        if(compatible==0)return Reason.NO_COMPLETE_MODE;
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

    /** Captured affine displacement of one offset unit on this row's frozen raster normal. */
    private double metricNormalPitch(int row) {
        work(EmissionWorkBudget.Operation.PREPARATION, 1);
        var normal = context.observations().get(row).normal();
        var transform = context.rasterTransform();
        double scale = transform.axisUnit()
            == org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform.AxisUnit.SOURCE_PIXEL
            ? transform.rasterPixelsPerSourcePixel() : 1;
        return Math.hypot((normal.x() * transform.xAxisEastMetersPerSourcePixel()
                + normal.y() * transform.yAxisEastMetersPerSourcePixel()) / scale,
            (normal.x() * transform.xAxisNorthMetersPerSourcePixel()
                + normal.y() * transform.yAxisNorthMetersPerSourcePixel()) / scale);
    }

    private boolean barrierBetween(int from,int to) {
        for(int row:mandatoryRows){work(EmissionWorkBudget.Operation.PREPARATION,1);if(row>from && row<to)return true;}
        for(var barrier:barriers){work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(barrier.leftRow()>from && barrier.leftRow()<to || barrier.rightRow()>from && barrier.rightRow()<to
                    || from<=barrier.leftRow() && to>=barrier.rightRow())return true;}
        return false;
    }

    private double minimumPitch() {
        if(context.resolution().nativePitchMeters().isEmpty())return Double.NaN;
        double minimum=context.resolution().nativePitchMeters().getAsDouble();
        for(var sample:context.resolution().spatialPitchSamples()) {
            work(EmissionWorkBudget.Operation.PREPARATION,1);
            if(sample.nativePitchMeters().isEmpty())return Double.NaN;
            minimum=Math.min(minimum,sample.nativePitchMeters().getAsDouble());
        }
        return minimum;
    }
    private double chainage(int row){return context.observations().get(row).location().cumulativeGroundDistanceMeters();}
    private RasterPoint point(RowState state) {
        var fact=context.observations().get(state.row());var p=fact.location().rasterPoint();var n=fact.normal();
        return new RasterPoint(p.x()+n.x()*state.offsetRasterPixels(),p.y()+n.y()*state.offsetRasterPixels());
    }
    private MetricPoint metric(RasterPoint point){work(EmissionWorkBudget.Operation.PREPARATION,1);return context.rasterTransform().pixelCenterToMetric(point.x(),point.y());}
    private static RasterPoint interpolate(RasterPoint a,RasterPoint b,double t){return new RasterPoint(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t);}
    private boolean authority(org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion region,
            MetricPoint a,MetricPoint b,AttemptMemoryLedger.Owner evaluation) {
        return region.containsSegmentWithConservativeEvents(a,b,CancellationProbe.NONE,n->work(EmissionWorkBudget.Operation.REGION_QUERY,n),evaluation);
    }

    private double[] probeParameters(RasterPoint a,RasterPoint b,double length,double pitch,AttemptMemoryLedger.Owner evaluation) {
        ScalarFieldGridView grid=context.nativeGrid();var ga=grid.toGrid(a);var gb=grid.toGrid(b);
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
    private void work(EmissionWorkBudget.Operation operation,long units){budget.charge(operation,units);}
    private void requireLive(){if(terminalFailure!=null)throw terminalFailure;if(owner.isClosed())throw new IllegalStateException("Connector session is closed");}
    @Override public void close(){owner.close();}
}
