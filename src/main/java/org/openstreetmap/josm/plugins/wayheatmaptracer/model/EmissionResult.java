package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    public EmissionResult(List<OriginalObservation> originalObservations,
            EmissionMapping mapping, List<Vertex> inferenceVertices, List<Vertex> smoothedVertices,
            List<Arc> inferenceArcs, List<Arc> postSmoothingArcs, Costs firstStage,
            List<Costs> intervalStages, Costs stitched, Costs postSmoothing,
            double sourceIntervalMassMeters, double measuredPhysicalLengthMeters,
            double totalPhysicalLengthMeters, EmissionWorkBudget.Snapshot work,
            long peakRetainedBytes, AutoCloseable receiptLifetime) {
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
        this.receiptLifetime = Objects.requireNonNull(receiptLifetime);
        if (mapping.observationCount() != this.originalObservations.size()
                || mapping.vertexOwners().size() != this.inferenceVertices.size()
                || this.smoothedVertices.size() != this.inferenceVertices.size()
                || this.inferenceArcs.size() != this.inferenceVertices.size() - 1
                || this.postSmoothingArcs.size() != this.inferenceArcs.size()
                || !finiteNonnegative(sourceIntervalMassMeters)
                || !finiteNonnegative(measuredPhysicalLengthMeters)
                || !finiteNonnegative(totalPhysicalLengthMeters)
                || measuredPhysicalLengthMeters > totalPhysicalLengthMeters + 1e-9 || peakRetainedBytes < 0)
            throw new IllegalArgumentException("Emission N/M dimensions or measures disagree");
        for (int i = 0; i < this.inferenceVertices.size(); i++) {
            if (!mapping.vertexOwners().get(i).equals(this.inferenceVertices.get(i).owner())
                    || !mapping.vertexOwners().get(i).equals(this.smoothedVertices.get(i).owner()))
                throw new IllegalArgumentException("Emission vertex owner differs from mapping");
        }
        double source = 0, measured = 0, length = 0;
        for (int i = 0; i < this.inferenceArcs.size(); i++) {
            Arc arc = this.inferenceArcs.get(i);
            if (arc.index() != i || this.postSmoothingArcs.get(i).index() != i)
                throw new IllegalArgumentException("Emission arc indices are not contiguous");
            source += arc.sourceSpanMeters(); length += arc.lengthMeters();
            if (arc.meanNativeIntensity().isPresent()) measured += arc.lengthMeters();
        }
        if (Math.abs(source - sourceIntervalMassMeters) > 1e-7
                || Math.abs(measured - measuredPhysicalLengthMeters) > 1e-7
                || Math.abs(length - totalPhysicalLengthMeters) > 1e-7)
            throw new IllegalArgumentException("Emission source or physical coverage sum disagrees");
    }

    private static boolean finiteNonnegative(double value) { return Double.isFinite(value) && value >= 0; }
    public List<OriginalObservation> originalObservations() { return originalObservations; }
    public EmissionMapping mapping() { return mapping; }
    public List<Vertex> inferenceVertices() { return inferenceVertices; }
    public List<Vertex> smoothedVertices() { return smoothedVertices; }
    public List<Arc> inferenceArcs() { return inferenceArcs; }
    public List<Arc> postSmoothingArcs() { return postSmoothingArcs; }
    public Costs firstStage() { return firstStage; }
    public List<Costs> intervalStages() { return intervalStages; }
    public Costs stitched() { return stitched; }
    public Costs postSmoothing() { return postSmoothing; }
    public double sourceIntervalMassMeters() { return sourceIntervalMassMeters; }
    public double measuredPhysicalLengthMeters() { return measuredPhysicalLengthMeters; }
    public double totalPhysicalLengthMeters() { return totalPhysicalLengthMeters; }
    public EmissionWorkBudget.Snapshot work() { return work; }
    public long peakRetainedBytes() { return peakRetainedBytes; }
    @Override public void close() {
        try { receiptLifetime.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
