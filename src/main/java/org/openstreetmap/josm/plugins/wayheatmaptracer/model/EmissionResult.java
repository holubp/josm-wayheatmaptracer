package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator;

/** An opt-in Legacy route with separate original observations and actual vertices. */
public final class EmissionResult implements AutoCloseable {
    public enum StateKind { OBSERVED_PEAK, BASE_CARRY, INTERVAL_TARGET, BOUNDARY_EVENT }
    public enum ArcKind { ORDINARY_MEASURED, ORDINARY_UNAVAILABLE, OPTIONAL_ADMITTED }

    /** Value snapshot of an original source observation; consumed rows still keep this record. */
    public record OriginalObservation(int originalRow, int retainedContextIndex,
            NativeConnectorEvaluator.SourceClass sourceClass, RasterPoint rasterAnchor,
            MetricPoint metricAnchor, double sourceChainageMeters, List<Integer> originalPeakIds) {
        public OriginalObservation {
            Objects.requireNonNull(sourceClass); Objects.requireNonNull(rasterAnchor);
            Objects.requireNonNull(metricAnchor);
            originalPeakIds = List.copyOf(Objects.requireNonNull(originalPeakIds));
            if (originalRow < 0 || retainedContextIndex < 0 || !Double.isFinite(sourceChainageMeters))
                throw new IllegalArgumentException("Original emission observation is incomplete");
        }
    }

    /** Factual source and model identity; tracker normalization is distinct from native pitch. */
    public record SourceReference(String sourceIdentity, String evidenceContentHash,
            String requestIdentity, String policyId, String modelPolicyId,
            double trackerNormalizationRasterPixels, double nativePitchMeters,
            boolean productionEligible) {
        public SourceReference {
            Objects.requireNonNull(sourceIdentity); Objects.requireNonNull(evidenceContentHash);
            Objects.requireNonNull(requestIdentity); Objects.requireNonNull(policyId);
            Objects.requireNonNull(modelPolicyId);
            if (!finitePositive(trackerNormalizationRasterPixels) || !finitePositive(nativePitchMeters))
                throw new IllegalArgumentException("Emission source scales must be factual positive values");
        }
    }

    public record SeedReference(int id, double offsetRasterPixels) {
        public SeedReference {
            if (id < 0 || !Double.isFinite(offsetRasterPixels))
                throw new IllegalArgumentException("Native seed reference is invalid");
        }
    }

    public record AnchorReference(int originalRow, int originalPeakId) {
        public AnchorReference {
            if (originalRow < 0 || originalPeakId < 0)
                throw new IllegalArgumentException("Native anchor reference is invalid");
        }
    }

    /** Old Legacy unary evidence is diagnostic only, never a selected emission cost. */
    public record NativePeakReference(int originalPeakId, double offsetRasterPixels,
            double intensity, boolean eligibleAfterSuppression,
            OptionalDouble nativeEvidenceReward, OptionalDouble nativeLocalPenalty,
            OptionalDouble nativeReferenceScore) {
        public NativePeakReference {
            Objects.requireNonNull(nativeEvidenceReward); Objects.requireNonNull(nativeLocalPenalty);
            Objects.requireNonNull(nativeReferenceScore);
            if (originalPeakId < 0 || !Double.isFinite(offsetRasterPixels)
                    || !Double.isFinite(intensity)
                    || eligibleAfterSuppression != nativeEvidenceReward.isPresent()
                    || eligibleAfterSuppression != nativeLocalPenalty.isPresent()
                    || eligibleAfterSuppression != nativeReferenceScore.isPresent())
                throw new IllegalArgumentException("Native peak reference is incomplete");
        }
    }

    public record NativeStateTable(int originalRow, NativeConnectorEvaluator.SourceClass sourceClass,
            RasterPoint nativeRasterAnchor, RasterPoint nativeNormal,
            List<NativePeakReference> peaks) {
        public NativeStateTable {
            Objects.requireNonNull(sourceClass); Objects.requireNonNull(nativeRasterAnchor);
            Objects.requireNonNull(nativeNormal);
            peaks = List.copyOf(Objects.requireNonNull(peaks));
            if (originalRow < 0) throw new IllegalArgumentException("Native row reference is invalid");
        }
    }

    /** Native carry origin is either an original seed or an original observed peak. */
    public record OriginReference(int originId, Optional<SeedReference> seed,
            Optional<AnchorReference> observedPeak) {
        public OriginReference {
            Objects.requireNonNull(seed); Objects.requireNonNull(observedPeak);
            if (originId < 0 || seed.isPresent() == observedPeak.isPresent())
                throw new IllegalArgumentException("Carry origin must name exactly one native source");
        }
    }

    /** A reached model-generated state is a possibility, never invented source support. */
    public record GeneratedStateReference(int originalRow, int nativeStateId, StateKind kind,
            double offsetRasterPixels, int originId,
            Optional<AnchorReference> leftAnchor, Optional<AnchorReference> rightAnchor) {
        public GeneratedStateReference {
            Objects.requireNonNull(kind); Objects.requireNonNull(leftAnchor);
            Objects.requireNonNull(rightAnchor);
            if (originalRow < 0 || nativeStateId < 0 || originId < 0
                    || !Double.isFinite(offsetRasterPixels)
                    || kind != StateKind.BASE_CARRY && kind != StateKind.INTERVAL_TARGET
                    || (kind == StateKind.INTERVAL_TARGET) != leftAnchor.isPresent()
                    || leftAnchor.isPresent() != rightAnchor.isPresent())
                throw new IllegalArgumentException("Generated state reference is incomplete");
        }
    }

    /** Actual selected interval path and owned endpoints, not only its cost row. */
    public record IntervalStageReference(int ordinal, AnchorReference leftAnchor,
            AnchorReference rightAnchor, List<Vertex> vertices, List<Arc> arcs, Costs costs) {
        public IntervalStageReference {
            Objects.requireNonNull(leftAnchor); Objects.requireNonNull(rightAnchor);
            vertices = List.copyOf(Objects.requireNonNull(vertices));
            arcs = List.copyOf(Objects.requireNonNull(arcs)); Objects.requireNonNull(costs);
            if (ordinal <= 0 || vertices.size() < 2 || arcs.size() != vertices.size() - 1)
                throw new IllegalArgumentException("Interval stage path is incomplete");
            double sum = arcs.stream().mapToDouble(arc -> arc.q() + arc.c() + arc.t() + arc.k()).sum();
            if (Math.abs(sum - costs.total()) > 1e-8 * Math.max(1, costs.total()))
                throw new IllegalArgumentException("Interval stage cost differs from its actual arcs");
        }
    }

    public record Provenance(SourceReference sourceReference, List<SeedReference> seedReferences,
            SeedReference chosenSeed, List<NativeStateTable> nativeStateTables,
            List<OriginReference> originReferences,
            List<GeneratedStateReference> generatedStateReferences,
            List<AnchorReference> eligibleAnchors, List<AnchorReference> selectedAnchors,
            List<Vertex> firstStageVertices, List<Arc> firstStageArcs,
            List<IntervalStageReference> intervalStageReferences) {
        public Provenance {
            Objects.requireNonNull(sourceReference); Objects.requireNonNull(chosenSeed);
            seedReferences = List.copyOf(Objects.requireNonNull(seedReferences));
            nativeStateTables = List.copyOf(Objects.requireNonNull(nativeStateTables));
            originReferences = List.copyOf(Objects.requireNonNull(originReferences));
            generatedStateReferences = List.copyOf(Objects.requireNonNull(generatedStateReferences));
            eligibleAnchors = List.copyOf(Objects.requireNonNull(eligibleAnchors));
            selectedAnchors = List.copyOf(Objects.requireNonNull(selectedAnchors));
            firstStageVertices = List.copyOf(Objects.requireNonNull(firstStageVertices));
            firstStageArcs = List.copyOf(Objects.requireNonNull(firstStageArcs));
            intervalStageReferences = List.copyOf(Objects.requireNonNull(intervalStageReferences));
            if (!seedReferences.contains(chosenSeed) || firstStageVertices.size() < 2
                    || firstStageArcs.size() != firstStageVertices.size() - 1)
                throw new IllegalArgumentException("Emission stage provenance is incomplete");
        }
    }

    /** No state or offset is invented for a boundary-only occurrence. */
    public record Vertex(MetricPoint position, EmissionMapping.VertexOwner owner,
            StateKind kind, Optional<Integer> nativeStateId, Optional<Double> offsetRasterPixels) {
        public Vertex {
            Objects.requireNonNull(position); Objects.requireNonNull(owner); Objects.requireNonNull(kind);
            nativeStateId = Objects.requireNonNull(nativeStateId);
            offsetRasterPixels = Objects.requireNonNull(offsetRasterPixels);
            boolean boundary = owner instanceof EmissionMapping.BoundaryVertex;
            if (boundary != (kind == StateKind.BOUNDARY_EVENT) || boundary != nativeStateId.isEmpty()
                    || boundary != offsetRasterPixels.isEmpty()
                    || offsetRasterPixels.isPresent() && !Double.isFinite(offsetRasterPixels.get())) {
                throw new IllegalArgumentException("Emission vertex ownership and numeric state disagree");
            }
        }
    }

    /** One actual arc and its own source mass, physical length, and typed image evidence. */
    public record Arc(int index, ArcKind kind, double sourceSpanMeters, double lengthMeters,
            double q, double c, double t, double k,
            Optional<Double> meanNativeIntensity, Optional<NativeConnectorEvaluator.Receipt> optionalReceipt,
            Optional<NativeConnectorEvaluator.OrdinaryPhysicalLeg> ordinaryPositive,
            Optional<NativeConnectorEvaluator.PhysicalDirectionCursor> ordinaryZeroCursor) {
        public Arc {
            Objects.requireNonNull(kind); meanNativeIntensity = Objects.requireNonNull(meanNativeIntensity);
            optionalReceipt = Objects.requireNonNull(optionalReceipt);
            ordinaryPositive = Objects.requireNonNull(ordinaryPositive);
            ordinaryZeroCursor = Objects.requireNonNull(ordinaryZeroCursor);
            if (index < 0 || !finiteNonnegative(sourceSpanMeters) || !finiteNonnegative(lengthMeters)
                    || !finiteNonnegative(q) || !finiteNonnegative(c) || !finiteNonnegative(t)
                    || !finiteNonnegative(k) || kind == ArcKind.OPTIONAL_ADMITTED != optionalReceipt.isPresent()
                    || (kind == ArcKind.ORDINARY_UNAVAILABLE) != meanNativeIntensity.isEmpty()
                    || optionalReceipt.isPresent() && (ordinaryPositive.isPresent() || ordinaryZeroCursor.isPresent())
                    || ordinaryPositive.isPresent() && ordinaryZeroCursor.isPresent()
                    || ordinaryPositive.isPresent() && lengthMeters <= 0
                    || ordinaryZeroCursor.isPresent() && lengthMeters != 0
                    || meanNativeIntensity.isPresent() && (!Double.isFinite(meanNativeIntensity.get())
                        || meanNativeIntensity.get() < 0 || meanNativeIntensity.get() > 1)) {
                throw new IllegalArgumentException("Emission arc evidence is inconsistent");
            }
        }
    }

    /** Decomposed objective; a post-smoothing total is evidence, never a DP optimum. */
    public record Costs(double q, double c, double t, double k) {
        public Costs {
            if (!finiteNonnegative(q) || !finiteNonnegative(c) || !finiteNonnegative(t) || !finiteNonnegative(k))
                throw new IllegalArgumentException("Emission costs must be finite and nonnegative");
        }
        public double total() { return q + c + t + k; }
    }

    private final List<OriginalObservation> originalObservations;
    private final EmissionMapping mapping;
    private final List<Vertex> inferenceVertices;
    private final List<Vertex> smoothedVertices;
    private final List<Arc> inferenceArcs;
    private final List<Arc> postSmoothingArcs;
    private final Costs firstStage;
    private final List<Costs> intervalStages;
    private final Costs stitched;
    private final Costs postSmoothing;
    private final double sourceIntervalMassMeters;
    private final double measuredPhysicalLengthMeters;
    private final double totalPhysicalLengthMeters;
    private final EmissionWorkBudget.Snapshot work;
    private final long peakRetainedBytes;
    private final AutoCloseable receiptLifetime;
    private final Provenance provenance;
    private volatile boolean closed;

    public EmissionResult(List<OriginalObservation> originalObservations,
            EmissionMapping mapping, List<Vertex> inferenceVertices, List<Vertex> smoothedVertices,
            List<Arc> inferenceArcs, List<Arc> postSmoothingArcs, Costs firstStage,
            List<Costs> intervalStages, Costs stitched, Costs postSmoothing,
            double sourceIntervalMassMeters, double measuredPhysicalLengthMeters,
            double totalPhysicalLengthMeters, EmissionWorkBudget.Snapshot work,
            long peakRetainedBytes, Provenance provenance, AutoCloseable receiptLifetime) {
        this.originalObservations = List.copyOf(Objects.requireNonNull(originalObservations));
        this.mapping = Objects.requireNonNull(mapping);
        this.inferenceVertices = List.copyOf(Objects.requireNonNull(inferenceVertices));
        this.smoothedVertices = List.copyOf(Objects.requireNonNull(smoothedVertices));
        this.inferenceArcs = List.copyOf(Objects.requireNonNull(inferenceArcs));
        this.postSmoothingArcs = List.copyOf(Objects.requireNonNull(postSmoothingArcs));
        this.firstStage = Objects.requireNonNull(firstStage);
        this.intervalStages = List.copyOf(Objects.requireNonNull(intervalStages));
        this.stitched = Objects.requireNonNull(stitched);
        this.postSmoothing = Objects.requireNonNull(postSmoothing);
        this.sourceIntervalMassMeters = sourceIntervalMassMeters;
        this.measuredPhysicalLengthMeters = measuredPhysicalLengthMeters;
        this.totalPhysicalLengthMeters = totalPhysicalLengthMeters;
        this.work = Objects.requireNonNull(work);
        this.peakRetainedBytes = peakRetainedBytes;
        this.provenance = Objects.requireNonNull(provenance);
        this.receiptLifetime = Objects.requireNonNull(receiptLifetime);
        if (mapping.observationCount() != this.originalObservations.size()
                || mapping.vertexOwners().size() != this.inferenceVertices.size()
                || this.smoothedVertices.size() != this.inferenceVertices.size()
                || this.inferenceArcs.size() != this.inferenceVertices.size() - 1
                || this.postSmoothingArcs.size() != this.inferenceArcs.size()
                || !finiteNonnegative(sourceIntervalMassMeters)
                || !finiteNonnegative(measuredPhysicalLengthMeters)
                || !finiteNonnegative(totalPhysicalLengthMeters)
                || measuredPhysicalLengthMeters > totalPhysicalLengthMeters + 1e-9
                || totalPhysicalLengthMeters <= 0 || peakRetainedBytes < 0
                || provenance.nativeStateTables().size() != this.originalObservations.size()
                || provenance.intervalStageReferences().size() != this.intervalStages.size())
            throw new IllegalArgumentException("Emission N/M dimensions or measures disagree");
        for (int i = 0; i < this.inferenceVertices.size(); i++) {
            if (!mapping.vertexOwners().get(i).equals(this.inferenceVertices.get(i).owner())
                    || !mapping.vertexOwners().get(i).equals(this.smoothedVertices.get(i).owner()))
                throw new IllegalArgumentException("Emission vertex owner differs from mapping");
        }
        double source = 0, measured = 0, length = 0;
        for (int i = 0; i < this.inferenceArcs.size(); i++) {
            Arc arc = this.inferenceArcs.get(i);
            Arc post = this.postSmoothingArcs.get(i);
            if (arc.index() != i || post.index() != i
                    || Math.abs(arc.sourceSpanMeters() - post.sourceSpanMeters()) > 1e-9)
                throw new IllegalArgumentException("Emission arc indices are not contiguous");
            source += arc.sourceSpanMeters(); length += post.lengthMeters();
            if (post.meanNativeIntensity().isPresent()) measured += post.lengthMeters();
        }
        if (Math.abs(source - sourceIntervalMassMeters) > 1e-7
                || Math.abs(measured - measuredPhysicalLengthMeters) > 1e-7
                || Math.abs(length - totalPhysicalLengthMeters) > 1e-7)
            throw new IllegalArgumentException("Emission source or physical coverage sum disagrees");
    }

    private static boolean finiteNonnegative(double value) { return Double.isFinite(value) && value >= 0; }
    private static boolean finitePositive(double value) { return Double.isFinite(value) && value > 0; }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("Emission result is closed");
    }
    public List<OriginalObservation> originalObservations() { requireOpen(); return originalObservations; }
    public EmissionMapping mapping() { requireOpen(); return mapping; }
    public List<Vertex> inferenceVertices() { requireOpen(); return inferenceVertices; }
    public List<Vertex> smoothedVertices() { requireOpen(); return smoothedVertices; }
    public List<Arc> inferenceArcs() { requireOpen(); return inferenceArcs; }
    public List<Arc> postSmoothingArcs() { requireOpen(); return postSmoothingArcs; }
    public Costs firstStage() { requireOpen(); return firstStage; }
    public List<Costs> intervalStages() { requireOpen(); return intervalStages; }
    public Costs stitched() { requireOpen(); return stitched; }
    public Costs postSmoothing() { requireOpen(); return postSmoothing; }
    public double sourceIntervalMassMeters() { requireOpen(); return sourceIntervalMassMeters; }
    public double measuredPhysicalLengthMeters() { requireOpen(); return measuredPhysicalLengthMeters; }
    public double totalPhysicalLengthMeters() { requireOpen(); return totalPhysicalLengthMeters; }
    public EmissionWorkBudget.Snapshot work() { requireOpen(); return work; }
    public long peakRetainedBytes() { requireOpen(); return peakRetainedBytes; }
    public SourceReference sourceReference() { requireOpen(); return provenance.sourceReference(); }
    public List<SeedReference> seedReferences() { requireOpen(); return provenance.seedReferences(); }
    public SeedReference chosenSeed() { requireOpen(); return provenance.chosenSeed(); }
    public List<NativeStateTable> nativeStateTables() { requireOpen(); return provenance.nativeStateTables(); }
    public List<OriginReference> originReferences() { requireOpen(); return provenance.originReferences(); }
    public List<GeneratedStateReference> generatedStateReferences() {
        requireOpen(); return provenance.generatedStateReferences();
    }
    public List<AnchorReference> eligibleAnchors() { requireOpen(); return provenance.eligibleAnchors(); }
    public List<AnchorReference> selectedAnchors() { requireOpen(); return provenance.selectedAnchors(); }
    public List<Vertex> firstStageVertices() { requireOpen(); return provenance.firstStageVertices(); }
    public List<Arc> firstStageArcs() { requireOpen(); return provenance.firstStageArcs(); }
    public List<IntervalStageReference> intervalStageReferences() {
        requireOpen(); return provenance.intervalStageReferences();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { receiptLifetime.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
