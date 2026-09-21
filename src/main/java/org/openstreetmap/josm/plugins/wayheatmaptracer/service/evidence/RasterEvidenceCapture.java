package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRasterGrid;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterResamplingProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;

/** Captures an acquired raster into an exact detached local-metric evidence grid. */
public final class RasterEvidenceCapture {
    static final long MAX_INPUT_PIXELS = 16_777_216L;
    static final long MAX_OUTPUT_PIXELS = 16_777_216L;
    static final int MAX_FIELDS = 16;
    static final long MAX_WORKING_BYTES = 512L * 1024L * 1024L;
    static final long MAX_RETAINED_EVIDENCE_BYTES = 256L * 1024L * 1024L;
    private static final long RETAINED_ARRAY_HEADER_BYTES = 32L;
    private static final long RETAINED_FIELD_OBJECT_AND_PROVENANCE_BYTES = 2_048L;
    private static final long RETAINED_FIELD_CONTAINER_BYTES = 128L;
    private static final long RETAINED_SNAPSHOT_OBJECT_AND_PROVENANCE_BYTES = 64L * 1024L;
    private static final long RETAINED_REFERENCE_BYTES = 8L;
    private static final long RETAINED_LIST_OBJECT_BYTES = 32L;
    private static final long RETAINED_STRING_OBJECT_BYTES = 24L;
    private static final long RETAINED_STRING_ARRAY_ALIGNMENT_SLACK_BYTES = 7L;
    private static final long RETAINED_STRING_CHAR_BYTES = 2L;
    private static final long RETAINED_METRIC_POINT_BYTES = 32L;
    private static final long RETAINED_POLYGON_OBJECT_BYTES = 64L;
    private static final long RETAINED_REGION_OBJECT_BYTES = 64L;
    private static final long RETAINED_RESOLUTION_SAMPLE_BYTES = 64L;
    private static final long RETAINED_FIXED_PROVENANCE_BYTES = 4_096L;
    private static final long RESAMPLE_OPERATION_CHARS =
            "strict-bilinear-metric-resample-v2".length();
    // FieldSpec validates kernels to at most 63 finite doubles; this bounds Arrays.toString.
    private static final long SEPARABLE_OPERATION_MAX_CHARS = 4_096L;

    /** One scalar field mapping and its immutable provenance. */
    public record FieldSpec(String name, IntToDoubleFunction argbMapping,
            EvidenceFieldLineage lineage, List<Double> separableKernel) {
        /** Validates a named scalar-before-filter capture operation. */
        public FieldSpec {
            if (name == null || name.isBlank() || argbMapping == null || lineage == null
                    || separableKernel == null) {
                throw new IllegalArgumentException("Raster evidence field specification is incomplete");
            }
            separableKernel = List.copyOf(separableKernel);
            validateKernel(separableKernel);
        }

        /** Creates an unfiltered field specification. */
        public static FieldSpec direct(String name, IntToDoubleFunction mapping,
                EvidenceFieldLineage lineage) {
            return new FieldSpec(name, mapping, lineage, List.of());
        }

        /** Creates a field with one known bounded scalar-domain separable filter. */
        public static FieldSpec separable(String name, IntToDoubleFunction mapping,
                EvidenceFieldLineage lineage, double[] kernel) {
            if (kernel == null) {
                throw new IllegalArgumentException("A separable filter kernel is required");
            }
            return new FieldSpec(name, mapping, lineage,
                    java.util.Arrays.stream(kernel).boxed().toList());
        }

        private static void validateKernel(List<Double> kernel) {
            if (kernel.isEmpty()) {
                return;
            }
            double sum = 0.0;
            for (Double weight : kernel) {
                if (weight == null || !Double.isFinite(weight) || weight < 0.0) {
                    throw new IllegalArgumentException(
                            "Filter weights must be finite and nonnegative");
                }
                sum += weight;
            }
            if (kernel.size() % 2 == 0 || kernel.size() > 63 || sum <= 0.0) {
                throw new IllegalArgumentException(
                        "A separable filter requires positive bounded odd support");
            }
        }

        private double[] copiedKernel() {
            return separableKernel.stream().mapToDouble(Double::doubleValue).toArray();
        }
    }

    /**
     * Maps valid input ARGB to scalar intensity, then rectifies it onto an exact metric output grid.
     * The producer's typed exact transform is invoked synchronously and is not retained.
     */
    public EvidenceSnapshot capture(String snapshotId, BufferedImage inputRaster,
            boolean[] acquisitionValidity, List<GeographicPoint> sourcePolyline,
            SupportedInputRasterTransform inputTransform,
            MetricRasterGrid outputGrid, EvidenceResolution sourceResolution,
            double decisionRadiusMeters, String sourceIdentity,
            EvidenceFieldLineage.AcquisitionKind acquisitionKind, List<FieldSpec> fieldSpecs,
            CancellationProbe cancellation) {
        return captureInternal(snapshotId, inputRaster, acquisitionValidity, sourcePolyline, null,
                inputTransform, outputGrid, sourceResolution, decisionRadiusMeters, sourceIdentity,
                acquisitionKind, fieldSpecs, cancellation);
    }

    /**
     * Captures evidence using metric source positions already validated by the owning slide capture.
     * Geographic source points remain retained provenance; this avoids a boundary-changing retransform.
     */
    public EvidenceSnapshot captureWithMetricSource(String snapshotId, BufferedImage inputRaster,
            boolean[] acquisitionValidity, List<GeographicPoint> sourcePolyline,
            List<MetricPoint> metricSource,
            SupportedInputRasterTransform inputTransform,
            MetricRasterGrid outputGrid, EvidenceResolution sourceResolution,
            double decisionRadiusMeters, String sourceIdentity,
            EvidenceFieldLineage.AcquisitionKind acquisitionKind, List<FieldSpec> fieldSpecs,
            CancellationProbe cancellation) {
        return captureInternal(snapshotId, inputRaster, acquisitionValidity, sourcePolyline, metricSource,
                inputTransform, outputGrid, sourceResolution, decisionRadiusMeters, sourceIdentity,
                acquisitionKind, fieldSpecs, cancellation);
    }

    private EvidenceSnapshot captureInternal(String snapshotId, BufferedImage inputRaster,
            boolean[] acquisitionValidity, List<GeographicPoint> sourcePolyline,
            List<MetricPoint> suppliedMetricSource,
            SupportedInputRasterTransform inputTransform,
            MetricRasterGrid outputGrid, EvidenceResolution sourceResolution,
            double decisionRadiusMeters, String sourceIdentity,
            EvidenceFieldLineage.AcquisitionKind acquisitionKind, List<FieldSpec> fieldSpecs,
            CancellationProbe cancellation) {
        validateInputs(snapshotId, inputRaster, acquisitionValidity, sourcePolyline,
                inputTransform, outputGrid, sourceResolution, decisionRadiusMeters,
                sourceIdentity, acquisitionKind, fieldSpecs, cancellation);

        int inputWidth = inputRaster.getWidth();
        int inputHeight = inputRaster.getHeight();
        int inputCount = Math.multiplyExact(inputWidth, inputHeight);
        long outputCount = (long) outputGrid.width() * outputGrid.height();
        validateResourceBounds(inputCount, outputCount, fieldSpecs);
        validateRetainedEvidenceBudget(inputCount, outputGrid.width(), outputGrid.height(),
                sourcePolyline, sourceResolution, snapshotId, sourceIdentity, inputTransform,
                fieldSpecs);
        validateFieldSpecs(fieldSpecs, acquisitionKind);
        validateSourcePolylinePoints(sourcePolyline);
        if (!inputTransform.supports(acquisitionKind)) {
            throw new IllegalArgumentException(
                    "Input raster transform kind is unsupported for this acquisition source");
        }
        cancellation.checkpoint();

        LocalMetricFrame frame = outputGrid.coordinateFrame();
        MetricRegion footprint = outputGrid.footprint();
        footprint.polygons().forEach(polygon -> polygon.forEach(frame::toGeographic));
        inputTransform.boundsForMetricCell(frame, footprint.polygons().get(0));
        List<MetricPoint> metricSource = suppliedMetricSource == null
                ? sourcePolyline.stream().map(frame::toMetric).toList()
                : List.copyOf(suppliedMetricSource);
        if (metricSource.size() != sourcePolyline.size() || metricSource.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Metric source polyline does not match geographic source occurrences");
        }
        MetricRegion decision = MetricCorridorRegion.aroundPolyline(metricSource, decisionRadiusMeters);
        if (!footprint.containsRegion(decision)) {
            throw new IllegalArgumentException("Metric output grid and evidence halo do not cover the decision corridor");
        }

        boolean[] inputValid = acquisitionValidity.clone();
        int[] argb = new int[inputCount];
        for (int y = 0; y < inputHeight; y++) {
            cancellation.checkpoint();
            inputRaster.getRGB(0, y, inputWidth, 1, argb, y * inputWidth, inputWidth);
        }
        InverseLookup lookup = inverseLookup(outputGrid, inputTransform,
                inputWidth, inputHeight, inputValid, cancellation);
        Map<String, ScalarEvidenceField> fields = new LinkedHashMap<>();
        for (FieldSpec spec : fieldSpecs) {
            double[] sourceValues = mapInput(argb, inputValid, inputWidth, inputHeight,
                    spec.argbMapping(), cancellation);
            ScalarEvidenceField resampled = resample(sourceValues, inputWidth, outputGrid,
                    lookup, spec.lineage(), cancellation);
            ScalarEvidenceField result = spec.separableKernel().isEmpty() ? resampled
                    : resampled.convolveSeparable(spec.copiedKernel(), cancellation::checkpoint);
            validatePostMapping(result, outputGrid, lookup);
            cancellation.checkpoint();
            fields.put(spec.name(), result);
        }

        EvidenceResolution outputResolution = sourceResolution.resampledTo(outputGrid.pitchMeters());
        RasterResamplingProvenance resampling = RasterResamplingProvenance.exactInverseBilinear(
                inputTransform.kindId(), inputTransform.parameterIdentity(),
                inputWidth, inputHeight,
                outputGrid.width(), outputGrid.height());
        return new EvidenceSnapshot(snapshotId, frame, outputGrid.transform(), outputResolution,
                decision, footprint, fields, resampling, sourceIdentity);
    }

    private static void validateInputs(String snapshotId, BufferedImage inputRaster,
            boolean[] acquisitionValidity, List<GeographicPoint> sourcePolyline,
            SupportedInputRasterTransform inputTransform,
            MetricRasterGrid outputGrid, EvidenceResolution sourceResolution,
            double decisionRadiusMeters, String sourceIdentity,
            EvidenceFieldLineage.AcquisitionKind acquisitionKind, List<FieldSpec> fieldSpecs,
            CancellationProbe cancellation) {
        if (snapshotId == null || snapshotId.isBlank() || inputRaster == null
                || inputRaster.getWidth() < 2 || inputRaster.getHeight() < 2
                || acquisitionValidity == null || sourcePolyline == null || sourcePolyline.size() < 2
                || inputTransform == null || outputGrid == null
                || sourceResolution == null || sourceResolution.resampledPitchMeters().isPresent()
                || !Double.isFinite(decisionRadiusMeters) || decisionRadiusMeters <= 0.0
                || sourceIdentity == null || sourceIdentity.isBlank() || acquisitionKind == null
                || fieldSpecs == null || fieldSpecs.isEmpty() || cancellation == null) {
            throw new IllegalArgumentException("Raster evidence capture is incomplete");
        }
        long inputPixels = (long) inputRaster.getWidth() * inputRaster.getHeight();
        if (inputPixels > Integer.MAX_VALUE || acquisitionValidity.length != inputPixels) {
            throw new IllegalArgumentException("Acquisition validity must match the input raster");
        }
    }

    private static void validateResourceBounds(long inputPixels, long outputPixels,
            List<FieldSpec> fieldSpecs) {
        int fieldCount = fieldSpecs.size();
        if (inputPixels > MAX_INPUT_PIXELS || outputPixels > MAX_OUTPUT_PIXELS
                || fieldCount > MAX_FIELDS) {
            throw new IllegalArgumentException("Raster capture exceeds its pixel or field budget");
        }
        if (estimatedPeakWorkingBytes(inputPixels, outputPixels, fieldSpecs) > MAX_WORKING_BYTES) {
            throw new IllegalArgumentException("Raster capture exceeds its working-memory budget");
        }
    }

    /**
     * Returns a conservative allocation peak for the complete supported operation graph.
     * Package visibility keeps resource-boundary tests allocation-free.
     */
    static long estimatedPeakWorkingBytes(long inputPixels, long outputPixels,
            List<FieldSpec> fieldSpecs) {
        if (inputPixels < 0 || outputPixels < 0 || fieldSpecs == null
                || fieldSpecs.isEmpty() || fieldSpecs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Raster capture resource inputs are inconsistent");
        }
        try {
            // Input validity, ARGB, mapped scalar values, and conservative raster-row overhead.
            long inputBytes = Math.multiplyExact(inputPixels, 16L);
            // Exact lookup x/y plus vertex and complete-cell support masks.
            long globalOutputBytes = Math.multiplyExact(outputPixels, 18L);
            long maximumOutputBytes = 0L;
            for (int index = 0; index < fieldSpecs.size(); index++) {
                FieldSpec field = fieldSpecs.get(index);
                // Ten bytes per prior retained field: scalar, vertex-valid, and cell-support arrays.
                long priorRetained = Math.multiplyExact(outputPixels,
                        Math.multiplyExact(10L, index));
                // Direct resampling peaks at eighteen bytes. A separable operation additionally
                // retains the resampled field, horizontal/output work arrays, propagated cell mask,
                // and constructor copies, for a conservative thirty-nine bytes.
                long currentOperation = Math.multiplyExact(outputPixels,
                        field.separableKernel().isEmpty() ? 18L : 39L);
                maximumOutputBytes = Math.max(maximumOutputBytes,
                        Math.addExact(priorRetained, currentOperation));
            }
            long logicalPeak = Math.addExact(inputBytes,
                    Math.addExact(globalOutputBytes, maximumOutputBytes));
            long proportionalOverhead = logicalPeak / 8L;
            return Math.addExact(Math.addExact(logicalPeak, proportionalOverhead),
                    16L * 1024L * 1024L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Raster capture resource estimate overflowed", exception);
        }
    }

    /**
     * Returns a conservative final-evidence estimate from already-counted retained quantities.
     * The count form is allocation-free and is intentionally separate from the public capture
     * path so boundary tests do not construct large source lists or rasters.
     */
    static long estimatedRetainedEvidenceBytesFromCounts(int outputWidth, int outputHeight,
            long sourcePointCount, long spatialPitchSampleCount, long snapshotIdChars,
            long sourceIdentityChars, long transformKindChars, long transformIdentityChars,
            long fieldNameChars, long paletteChars, long lineageOperationCount,
            long lineageOperationChars, int fieldCount) {
        validateRetainedCountInputs(outputWidth, outputHeight, sourcePointCount,
                spatialPitchSampleCount, snapshotIdChars, sourceIdentityChars,
                transformKindChars, transformIdentityChars, fieldNameChars, paletteChars,
                lineageOperationCount, lineageOperationChars, fieldCount);
        try {
            long outputPixels = Math.multiplyExact((long) outputWidth, (long) outputHeight);
            long interpolationCells = Math.multiplyExact(
                    (long) outputWidth - 1L, (long) outputHeight - 1L);
            long finalArrays = finalArrayBytes(outputPixels, interpolationCells, fieldCount);
            long decisionPolygons = Math.max(1L, sourcePointCount - 1L);
            long geometry = regionBytes(decisionPolygons);
            geometry = Math.addExact(geometry, regionBytes(1L));
            long resolution = Math.addExact(RETAINED_LIST_OBJECT_BYTES,
                    Math.addExact(Math.multiplyExact(spatialPitchSampleCount,
                            RETAINED_RESOLUTION_SAMPLE_BYTES),
                            Math.multiplyExact(spatialPitchSampleCount,
                                    RETAINED_REFERENCE_BYTES)));
            long strings = 0L;
            strings = Math.addExact(strings, stringBytes(snapshotIdChars, 1L));
            strings = Math.addExact(strings, stringBytes(sourceIdentityChars, 1L));
            strings = Math.addExact(strings, stringBytes(transformKindChars, 1L));
            strings = Math.addExact(strings, stringBytes(transformIdentityChars, 1L));
            strings = Math.addExact(strings, stringBytes(fieldNameChars, fieldCount));
            strings = Math.addExact(strings, stringBytes(paletteChars, fieldCount));
            strings = Math.addExact(strings,
                    stringBytes(lineageOperationChars, lineageOperationCount));
            long lineageLists = Math.addExact(
                    Math.multiplyExact((long) fieldCount, RETAINED_LIST_OBJECT_BYTES),
                    Math.multiplyExact(lineageOperationCount, RETAINED_REFERENCE_BYTES));
            long fieldContainers = Math.multiplyExact((long) fieldCount,
                    Math.addExact(RETAINED_FIELD_CONTAINER_BYTES, RETAINED_REFERENCE_BYTES));
            long metadata = Math.addExact(geometry, resolution);
            metadata = Math.addExact(metadata, strings);
            metadata = Math.addExact(metadata, lineageLists);
            metadata = Math.addExact(metadata,
                    Math.addExact(RETAINED_FIXED_PROVENANCE_BYTES,
                            RETAINED_SNAPSHOT_OBJECT_AND_PROVENANCE_BYTES));
            return Math.addExact(Math.addExact(finalArrays,
                    Math.addExact(Math.multiplyExact((long) fieldCount,
                            RETAINED_FIELD_OBJECT_AND_PROVENANCE_BYTES), fieldContainers)),
                    metadata);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Raster retained-evidence estimate overflowed", exception);
        }
    }

    /** Returns a conservative retained estimate from the actual bounded metadata objects. */
    static long estimatedRetainedEvidenceBytes(int outputWidth, int outputHeight,
            List<GeographicPoint> sourcePolyline, EvidenceResolution sourceResolution,
            String snapshotId, String sourceIdentity,
            SupportedInputRasterTransform inputTransform, List<FieldSpec> fieldSpecs) {
        validateRetainedMetadataInputs(outputWidth, outputHeight, sourcePolyline,
                sourceResolution, snapshotId, sourceIdentity, inputTransform, fieldSpecs);
        int fieldCount = fieldSpecs.size();
        long sourcePointCount = sourcePolyline.size();
        long sampleCount = sourceResolution.spatialPitchSamples().size();
        long fieldNameChars = 0L;
        long paletteChars = 0L;
        long lineageOperationCount = 0L;
        for (FieldSpec field : fieldSpecs) {
            fieldNameChars = checkedLengthAdd(fieldNameChars, field.name().length());
            paletteChars = checkedLengthAdd(paletteChars, field.lineage().sourcePalette().length());
            lineageOperationCount = checkedCountAdd(lineageOperationCount,
                    field.lineage().scalarOperations().size());
            lineageOperationCount = checkedCountAdd(lineageOperationCount, 1L);
            if (!field.separableKernel().isEmpty()) {
                lineageOperationCount = checkedCountAdd(lineageOperationCount, 1L);
            }
        }
        String transformKind = inputTransform.kindId();
        String transformIdentity = inputTransform.parameterIdentity();
        long lowerBound = estimatedRetainedEvidenceBytesFromCounts(outputWidth, outputHeight,
                sourcePointCount, sampleCount, snapshotId.length(), sourceIdentity.length(),
                transformKind.length(), transformIdentity.length(), fieldNameChars,
                paletteChars, lineageOperationCount, 0L, fieldCount);
        if (lowerBound > MAX_RETAINED_EVIDENCE_BYTES) {
            return lowerBound;
        }
        long lineageOperationChars = 0L;
        for (FieldSpec field : fieldSpecs) {
            for (String operation : field.lineage().scalarOperations()) {
                lineageOperationChars = checkedLengthAdd(lineageOperationChars, operation.length());
            }
            lineageOperationChars = checkedLengthAdd(lineageOperationChars, RESAMPLE_OPERATION_CHARS);
            if (!field.separableKernel().isEmpty()) {
                lineageOperationChars = checkedLengthAdd(lineageOperationChars,
                        SEPARABLE_OPERATION_MAX_CHARS);
            }
        }
        return estimatedRetainedEvidenceBytesFromCounts(outputWidth, outputHeight,
                sourcePointCount, sampleCount, snapshotId.length(), sourceIdentity.length(),
                transformKind.length(), transformIdentity.length(), fieldNameChars,
                paletteChars, lineageOperationCount, lineageOperationChars, fieldCount);
    }

    /** Rejects counted retained evidence over the independent 256 MiB ceiling. */
    static void validateRetainedEvidenceBudgetFromCounts(int outputWidth, int outputHeight,
            long sourcePointCount, long spatialPitchSampleCount, long snapshotIdChars,
            long sourceIdentityChars, long transformKindChars, long transformIdentityChars,
            long fieldNameChars, long paletteChars, long lineageOperationCount,
            long lineageOperationChars, int fieldCount) {
        if (estimatedRetainedEvidenceBytesFromCounts(outputWidth, outputHeight, sourcePointCount,
                spatialPitchSampleCount, snapshotIdChars, sourceIdentityChars,
                transformKindChars, transformIdentityChars, fieldNameChars, paletteChars,
                lineageOperationCount, lineageOperationChars, fieldCount)
                > MAX_RETAINED_EVIDENCE_BYTES) {
            throw new IllegalArgumentException(
                    "Raster capture exceeds its final retained-evidence memory budget");
        }
    }

    /** Applies retained admission before source geometry, raster copies, lookup, or callbacks. */
    static void validateRetainedEvidenceBudget(long inputPixels, int outputWidth, int outputHeight,
            List<GeographicPoint> sourcePolyline, EvidenceResolution sourceResolution,
            String snapshotId, String sourceIdentity,
            SupportedInputRasterTransform inputTransform, List<FieldSpec> fieldSpecs) {
        long retained = estimatedRetainedEvidenceBytes(outputWidth, outputHeight, sourcePolyline,
                sourceResolution, snapshotId, sourceIdentity, inputTransform, fieldSpecs);
        if (retained > MAX_RETAINED_EVIDENCE_BYTES) {
            throw new IllegalArgumentException(
                    "Raster capture exceeds its final retained-evidence memory budget");
        }
        long outputPixels = Math.multiplyExact((long) outputWidth, (long) outputHeight);
        long finalArrays = finalArrayBytes(outputPixels,
                Math.multiplyExact((long) outputWidth - 1L, (long) outputHeight - 1L),
                fieldSpecs.size());
        long retainedMetadata = Math.subtractExact(retained, finalArrays);
        long sourceMetricList = Math.addExact(RETAINED_LIST_OBJECT_BYTES,
                Math.addExact(Math.multiplyExact(sourcePolyline.size(), RETAINED_REFERENCE_BYTES),
                        Math.multiplyExact(sourcePolyline.size(), RETAINED_METRIC_POINT_BYTES)));
        long workingExtra = Math.addExact(retainedMetadata, sourceMetricList);
        long working = Math.addExact(estimatedPeakWorkingBytes(inputPixels, outputPixels, fieldSpecs),
                workingExtra);
        if (working > MAX_WORKING_BYTES) {
            throw new IllegalArgumentException(
                    "Raster capture exceeds its working-memory budget including retained metadata");
        }
    }

    private static void validateRetainedMetadataInputs(int outputWidth, int outputHeight,
            List<GeographicPoint> sourcePolyline, EvidenceResolution sourceResolution,
            String snapshotId, String sourceIdentity,
            SupportedInputRasterTransform inputTransform, List<FieldSpec> fieldSpecs) {
        if (outputWidth <= 0 || outputHeight <= 0 || sourcePolyline == null
                || sourcePolyline.size() < 2 || sourceResolution == null
                || sourceResolution.spatialPitchSamples() == null
                || sourceResolution.spatialPitchSamples().isEmpty() || snapshotId == null
                || sourceIdentity == null || inputTransform == null || fieldSpecs == null
                || fieldSpecs.isEmpty() || fieldSpecs.size() > MAX_FIELDS
                || fieldSpecs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Raster retained-evidence inputs are inconsistent");
        }
    }

    private static void validateRetainedCountInputs(int outputWidth, int outputHeight,
            long sourcePointCount, long spatialPitchSampleCount, long snapshotIdChars,
            long sourceIdentityChars, long transformKindChars, long transformIdentityChars,
            long fieldNameChars, long paletteChars, long lineageOperationCount,
            long lineageOperationChars, int fieldCount) {
        if (outputWidth <= 0 || outputHeight <= 0 || sourcePointCount < 2
                || spatialPitchSampleCount < 1 || snapshotIdChars < 0 || sourceIdentityChars < 0
                || transformKindChars < 0 || transformIdentityChars < 0 || fieldNameChars < 0
                || paletteChars < 0 || lineageOperationCount < 0 || lineageOperationChars < 0
                || fieldCount < 1 || fieldCount > MAX_FIELDS) {
            throw new IllegalArgumentException("Raster retained-evidence counts are inconsistent");
        }
    }

    private static long finalArrayBytes(long outputPixels, long interpolationCells,
            int fieldCount) {
        try {
            long arrayPayload = Math.addExact(Math.addExact(Math.multiplyExact(outputPixels, 8L),
                    outputPixels), interpolationCells);
            long perField = Math.addExact(arrayPayload,
                    Math.multiplyExact(3L, RETAINED_ARRAY_HEADER_BYTES));
            return Math.multiplyExact(perField, fieldCount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Raster retained-evidence estimate overflowed", exception);
        }
    }

    private static long regionBytes(long polygonCount) {
        try {
            long polygon = Math.addExact(RETAINED_POLYGON_OBJECT_BYTES,
                    Math.addExact(Math.multiplyExact(4L,
                            Math.addExact(RETAINED_METRIC_POINT_BYTES, RETAINED_REFERENCE_BYTES)),
                            RETAINED_LIST_OBJECT_BYTES));
            return Math.addExact(RETAINED_REGION_OBJECT_BYTES,
                    Math.multiplyExact(polygonCount, polygon));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Raster retained-evidence estimate overflowed", exception);
        }
    }

    private static long stringBytes(long characterCount, long occurrenceCount) {
        try {
            long perOccurrence = Math.addExact(RETAINED_STRING_OBJECT_BYTES,
                    Math.addExact(RETAINED_ARRAY_HEADER_BYTES,
                            RETAINED_STRING_ARRAY_ALIGNMENT_SLACK_BYTES));
            return Math.addExact(Math.multiplyExact(occurrenceCount, perOccurrence),
                    Math.multiplyExact(characterCount, RETAINED_STRING_CHAR_BYTES));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Raster retained-evidence estimate overflowed", exception);
        }
    }

    private static long checkedLengthAdd(long left, long right) {
        if (right < 0L) {
            throw new IllegalArgumentException("Raster retained-evidence string length is invalid");
        }
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Raster retained-evidence estimate overflowed", exception);
        }
    }

    private static long checkedCountAdd(long left, long right) {
        if (right < 0L) {
            throw new IllegalArgumentException("Raster retained-evidence count is invalid");
        }
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Raster retained-evidence estimate overflowed", exception);
        }
    }

    private static void validateSourcePolylinePoints(List<GeographicPoint> sourcePolyline) {
        for (GeographicPoint point : sourcePolyline) {
            if (point == null) {
                throw new IllegalArgumentException("Source polyline contains a null point");
            }
        }
    }

    private static void validateFieldSpecs(List<FieldSpec> fieldSpecs,
            EvidenceFieldLineage.AcquisitionKind acquisitionKind) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (FieldSpec spec : fieldSpecs) {
            if (spec == null || spec.lineage().acquisitionKind() != acquisitionKind) {
                throw new IllegalArgumentException("Field lineage does not match the capture source");
            }
            if (!names.add(spec.name())) {
                throw new IllegalArgumentException("Duplicate scalar evidence field name");
            }
        }
    }

    private static InverseLookup inverseLookup(MetricRasterGrid grid,
            SupportedInputRasterTransform inputTransform,
            int inputWidth, int inputHeight, boolean[] inputValid, CancellationProbe cancellation) {
        int count = Math.multiplyExact(grid.width(), grid.height());
        double[] sourceX = new double[count];
        double[] sourceY = new double[count];
        boolean[] valid = new boolean[count];
        for (int y = 0; y < grid.height(); y++) {
            cancellation.checkpoint();
            for (int x = 0; x < grid.width(); x++) {
                int index = y * grid.width() + x;
                MetricPoint metric = grid.pixelCenterToMetric(x, y);
                RasterPoint source = inputTransform.toRasterCenter(
                        grid.coordinateFrame().toGeographic(metric));
                sourceX[index] = source.x();
                sourceY[index] = source.y();
                valid[index] = strictFourCornerSupport(source.x(), source.y(),
                        inputWidth, inputHeight, inputValid);
            }
        }
        boolean[] interpolationValid = new boolean[
                Math.multiplyExact(grid.width() - 1, grid.height() - 1)];
        for (int y = 0; y < grid.height() - 1; y++) {
            cancellation.checkpoint();
            for (int x = 0; x < grid.width() - 1; x++) {
                List<MetricPoint> corners = List.of(
                        grid.pixelCenterToMetric(x, y),
                        grid.pixelCenterToMetric(x + 1, y),
                        grid.pixelCenterToMetric(x + 1, y + 1),
                        grid.pixelCenterToMetric(x, y + 1));
                SupportedInputRasterTransform.RasterBounds bounds =
                        inputTransform.boundsForMetricCell(grid.coordinateFrame(), corners);
                interpolationValid[y * (grid.width() - 1) + x] =
                        completeBoundsSupport(bounds, inputWidth, inputHeight,
                                inputValid, cancellation);
            }
        }
        return new InverseLookup(sourceX, sourceY, valid, interpolationValid);
    }

    private static boolean completeBoundsSupport(
            SupportedInputRasterTransform.RasterBounds bounds,
            int width, int height, boolean[] valid, CancellationProbe cancellation) {
        if (bounds.minimumX() < 0.0 || bounds.minimumY() < 0.0
                || bounds.maximumX() >= width - 1.0 || bounds.maximumY() >= height - 1.0) {
            return false;
        }
        int minimumX = (int) Math.floor(bounds.minimumX());
        int minimumY = (int) Math.floor(bounds.minimumY());
        int maximumX = (int) Math.floor(bounds.maximumX()) + 1;
        int maximumY = (int) Math.floor(bounds.maximumY()) + 1;
        for (int y = minimumY; y <= maximumY; y++) {
            cancellation.checkpoint();
            for (int x = minimumX; x <= maximumX; x++) {
                if (!valid[y * width + x]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean strictFourCornerSupport(double x, double y, int width, int height,
            boolean[] valid) {
        if (x < 0.0 || y < 0.0 || x >= width - 1.0 || y >= height - 1.0) {
            return false;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int upperLeft = y0 * width + x0;
        return valid[upperLeft] && valid[upperLeft + 1]
                && valid[upperLeft + width] && valid[upperLeft + width + 1];
    }

    private static double[] mapInput(int[] argb, boolean[] valid, int width, int height,
            IntToDoubleFunction mapping, CancellationProbe cancellation) {
        double[] values = new double[argb.length];
        for (int y = 0; y < height; y++) {
            cancellation.checkpoint();
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!valid[index]) {
                    values[index] = Double.NaN;
                    continue;
                }
                double value = mapping.applyAsDouble(argb[index]);
                if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException("Valid scalar evidence must be finite and normalized");
                }
                values[index] = value;
            }
        }
        return values;
    }

    private static ScalarEvidenceField resample(double[] sourceValues, int sourceWidth,
            MetricRasterGrid grid, InverseLookup lookup, EvidenceFieldLineage lineage,
            CancellationProbe cancellation) {
        double[] output = new double[lookup.valid().length];
        for (int y = 0; y < grid.height(); y++) {
            cancellation.checkpoint();
            for (int x = 0; x < grid.width(); x++) {
                int index = y * grid.width() + x;
                if (!lookup.valid()[index]) {
                    output[index] = Double.NaN;
                    continue;
                }
                double sourceX = lookup.sourceX()[index];
                double sourceY = lookup.sourceY()[index];
                int x0 = (int) Math.floor(sourceX);
                int y0 = (int) Math.floor(sourceY);
                double xFraction = sourceX - x0;
                double yFraction = sourceY - y0;
                int upperLeft = y0 * sourceWidth + x0;
                double top = sourceValues[upperLeft] * (1.0 - xFraction)
                        + sourceValues[upperLeft + 1] * xFraction;
                double bottom = sourceValues[upperLeft + sourceWidth] * (1.0 - xFraction)
                        + sourceValues[upperLeft + sourceWidth + 1] * xFraction;
                output[index] = Math.max(0.0, Math.min(1.0,
                        top * (1.0 - yFraction) + bottom * yFraction));
            }
        }
        return new ScalarEvidenceField(grid.width(), grid.height(), output,
                lookup.valid(), lookup.interpolationValid(),
                lineage.filtered("strict-bilinear-metric-resample-v2"));
    }

    private static void validatePostMapping(ScalarEvidenceField result, MetricRasterGrid grid,
            InverseLookup resampledSupport) {
        if (result == null || result.width() != grid.width() || result.height() != grid.height()) {
            throw new IllegalArgumentException("Post-mapping scalar operation changed the metric raster frame");
        }
        boolean[] resultValidity = result.copiedValidity();
        for (int index = 0; index < resultValidity.length; index++) {
            if (resultValidity[index] && !resampledSupport.valid()[index]) {
                throw new IllegalArgumentException("Post-mapping scalar operation resurrected invalid support");
            }
        }
        boolean[] resultInterpolationValidity = result.copiedInterpolationValidity();
        for (int index = 0; index < resultInterpolationValidity.length; index++) {
            if (resultInterpolationValidity[index]
                    && !resampledSupport.interpolationValid()[index]) {
                throw new IllegalArgumentException(
                        "Post-mapping scalar operation resurrected invalid cell support");
            }
        }
    }

    private record InverseLookup(double[] sourceX, double[] sourceY,
            boolean[] valid, boolean[] interpolationValid) {
    }
}
