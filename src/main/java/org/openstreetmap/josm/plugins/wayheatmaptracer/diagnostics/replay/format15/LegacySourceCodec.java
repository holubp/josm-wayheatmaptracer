package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyManagedProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyVisibleProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

/** Bounded lossless codec for frozen Legacy visible and managed source captures. */
public final class LegacySourceCodec {
    private static final int MAGIC = 0x57544c53;
    private static final int VERSION = 1;
    private static final int VISIBLE = 1;
    private static final int MANAGED = 2;
    private static final int MAX_TEXT_BYTES = 16_384;
    private static final int MAX_MOSAICS = 128;
    private static final int MAX_ANCHORS = 250_000;
    private static final int MAX_DETECTORS = 128;
    private static final int MAX_DIMENSION = 32_768;
    private static final long MAX_RETAINED_RASTER_BYTES = 64L * 1024L * 1024L;

    private LegacySourceCodec() { }

    /** Encodes visible pixels, exact geometry/settings, and detector order without capture receipts. */
    public static byte[] encode(LegacyVisibleProfileSampler.Input input) {
        if (input == null) throw new IllegalArgumentException("Frozen visible Legacy input is required");
        try {
            BoundedOutput bytes = new BoundedOutput();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                header(out, VISIBLE);
                LiveBPreviewService.VisibleRaster raster = input.raster();
                pixelsBound(raster.width(), raster.height(), 1);
                out.writeInt(raster.width());
                out.writeInt(raster.height());
                out.writeDouble(raster.minimumEast());
                out.writeDouble(raster.minimumNorth());
                out.writeDouble(raster.maximumEast());
                out.writeDouble(raster.maximumNorth());
                out.writeDouble(raster.projectionUnitsPerViewPixel());
                out.writeDouble(raster.groundMetersPerViewPixel());
                out.writeBoolean(raster.nativePitchMeters().isPresent());
                if (raster.nativePitchMeters().isPresent()) out.writeDouble(raster.nativePitchMeters().getAsDouble());
                safeString(out, raster.sourceIdentity());
                safeString(out, raster.projectionCode());
                compressedPixels(out, raster.argb(), raster.width(), raster.height());
                count(out, input.anchors().size(), MAX_ANCHORS);
                for (var anchor : input.anchors()) {
                    out.writeDouble(anchor.source().east());
                    out.writeDouble(anchor.source().north());
                    out.writeDouble(anchor.raster().x());
                    out.writeDouble(anchor.raster().y());
                    out.writeDouble(anchor.cumulativeGroundDistanceMeters());
                }
                strings(out, input.detectors(), MAX_DETECTORS, true);
                out.writeInt(input.options().halfWidthPx());
                out.writeInt(input.options().stepPx());
                out.writeDouble(input.options().trackerNormalizationRasterPx());
                enumName(out, input.options().intensityMode());
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Frozen Legacy source exceeds the Format-15 artifact budget", failure);
        }
    }

    /** Decodes a visible source after validating limits before allocating pixels or profile metadata. */
    public static LegacyVisibleProfileSampler.Input decodeVisible(byte[] bytes) {
        try (DataInputStream in = input(bytes, VISIBLE)) {
            int width = in.readInt();
            int height = in.readInt();
            pixelsBound(width, height, 1);
            double minEast = finite(in.readDouble());
            double minNorth = finite(in.readDouble());
            double maxEast = finite(in.readDouble());
            double maxNorth = finite(in.readDouble());
            double projectionUnits = finite(in.readDouble());
            double groundMeters = finite(in.readDouble());
            OptionalDouble nativePitch = in.readBoolean()
                    ? OptionalDouble.of(positive(in.readDouble())) : OptionalDouble.empty();
            String sourceIdentity = safeString(in);
            String projectionCode = safeString(in);
            int[] argb = readPixels(in, width, height);
            int anchorCount = count(in, MAX_ANCHORS);
            if (anchorCount < 2) throw malformed("Visible source has too few anchors");
            var anchors = new ArrayList<LegacyVisibleProfileSampler.Anchor>(anchorCount);
            for (int index = 0; index < anchorCount; index++) {
                anchors.add(new LegacyVisibleProfileSampler.Anchor(
                        new ProjectedPoint(finite(in.readDouble()), finite(in.readDouble())),
                        new RasterPoint(finite(in.readDouble()), finite(in.readDouble())),
                        nonnegative(in.readDouble())));
            }
            List<String> detectors = strings(in, MAX_DETECTORS, true);
            int halfWidth = in.readInt();
            int step = in.readInt();
            double normalization = positive(in.readDouble());
            IntensitySamplingMode intensity = enumValue(in, IntensitySamplingMode.class);
            requireEnd(in);
            var raster = new LiveBPreviewService.VisibleRaster(width, height, argb,
                    minEast, minNorth, maxEast, maxNorth, projectionUnits, groundMeters,
                    nativePitch, sourceIdentity, projectionCode);
            return new LegacyVisibleProfileSampler.Input(raster, anchors, detectors,
                    new LegacyVisibleProfileSampler.Options(halfWidth, step, normalization, intensity));
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument) throw argument;
            throw malformed("Malformed frozen Legacy visible source", failure);
        }
    }

    /** Encodes managed used-mosaic pixels and preserves native insertion and detector order. */
    public static byte[] encode(LegacyManagedProfileSampler.Input input) {
        if (input == null) throw new IllegalArgumentException("Frozen managed Legacy input is required");
        try {
            BoundedOutput bytes = new BoundedOutput();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                header(out, MANAGED);
                enumName(out, input.inferenceMode());
                out.writeInt(input.requestedInferenceZoom());
                out.writeInt(input.requestedValidationZoom());
                out.writeInt(input.inferenceZoom());
                out.writeInt(input.validationZoom());
                if (input.tileSize() > 8192) throw malformed("Managed tile size exceeds limits");
                out.writeInt(input.tileSize());
                samplingParameters(out, input.inferenceParameters());
                samplingParameters(out, input.validationParameters());
                count(out, input.mosaics().size(), MAX_MOSAICS);
                long retainedBytes = 0;
                for (Map.Entry<String, LegacyManagedProfileSampler.Mosaic> entry : input.mosaics().entrySet()) {
                    LegacyManagedProfileSampler.Mosaic mosaic = entry.getValue();
                    safeString(out, entry.getKey());
                    safeString(out, mosaic.color());
                    out.writeInt(mosaic.zoom());
                    out.writeDouble(mosaic.originWorldPxX());
                    out.writeDouble(mosaic.originWorldPxY());
                    pixelsBound(mosaic.width(), mosaic.height(), 1);
                    retainedBytes = addRasterBytes(retainedBytes, mosaic.width(), mosaic.height());
                    out.writeInt(mosaic.width());
                    out.writeInt(mosaic.height());
                    samplingParameters(out, mosaic.parameters());
                    out.writeBoolean(mosaic.stableInferenceRaster());
                    out.writeDouble(mosaic.virtualRasterScale());
                    compressedPixels(out, mosaic.argb(), mosaic.width(), mosaic.height());
                }
                count(out, input.sourcePolyline().size(), MAX_ANCHORS);
                for (ProjectedPoint point : input.sourcePolyline()) {
                    out.writeDouble(point.east());
                    out.writeDouble(point.north());
                }
                safeString(out, input.projectionCode());
                safeString(out, input.selectedPalette());
                strings(out, input.detectors(), MAX_DETECTORS, true);
                enumName(out, input.intensityMode());
                out.writeDouble(input.trackerNormalizationRasterPx());
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Frozen Legacy source exceeds the Format-15 artifact budget", failure);
        }
    }

    /** Decodes managed mosaics after enforcing count, dimension, retained-byte, and stream budgets. */
    public static LegacyManagedProfileSampler.Input decodeManaged(byte[] bytes) {
        try (DataInputStream in = input(bytes, MANAGED)) {
            InferenceMode mode = enumValue(in, InferenceMode.class);
            int requestedInference = in.readInt();
            int requestedValidation = in.readInt();
            int inferenceZoom = in.readInt();
            int validationZoom = in.readInt();
            int tileSize = in.readInt();
            if (tileSize <= 0 || tileSize > 8192) throw malformed("Managed tile size exceeds limits");
            var inference = samplingParameters(in);
            var validation = samplingParameters(in);
            int mosaicCount = count(in, MAX_MOSAICS);
            if (mosaicCount == 0) throw malformed("Managed source has no mosaics");
            var mosaics = new LinkedHashMap<String, LegacyManagedProfileSampler.Mosaic>();
            long retainedBytes = 0;
            for (int index = 0; index < mosaicCount; index++) {
                String key = safeString(in);
                String color = safeString(in);
                int zoom = in.readInt();
                double originX = finite(in.readDouble());
                double originY = finite(in.readDouble());
                int width = in.readInt();
                int height = in.readInt();
                pixelsBound(width, height, 1);
                retainedBytes = addRasterBytes(retainedBytes, width, height);
                var parameters = samplingParameters(in);
                boolean stable = in.readBoolean();
                double virtualScale = positive(in.readDouble());
                int[] argb = readPixels(in, width, height);
                var mosaic = new LegacyManagedProfileSampler.Mosaic(color, zoom, originX, originY,
                        width, height, argb, parameters, stable, virtualScale);
                if (!key.equals(color + "@" + zoom) || mosaics.putIfAbsent(key, mosaic) != null) {
                    throw malformed("Managed source mosaic identity is duplicated or inconsistent");
                }
            }
            int sourceCount = count(in, MAX_ANCHORS);
            if (sourceCount < 2) throw malformed("Managed source geometry has too few points");
            var source = new ArrayList<ProjectedPoint>(sourceCount);
            for (int index = 0; index < sourceCount; index++) {
                source.add(new ProjectedPoint(finite(in.readDouble()), finite(in.readDouble())));
            }
            String projectionCode = safeString(in);
            String selectedPalette = safeString(in);
            List<String> detectors = strings(in, MAX_DETECTORS, true);
            IntensitySamplingMode intensity = enumValue(in, IntensitySamplingMode.class);
            double normalization = positive(in.readDouble());
            requireEnd(in);
            return new LegacyManagedProfileSampler.Input(mode, requestedInference, requestedValidation,
                    inferenceZoom, validationZoom, tileSize, mosaics, inference, validation, source,
                    projectionCode, selectedPalette, detectors, intensity, normalization);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument) throw argument;
            throw malformed("Malformed frozen Legacy managed source", failure);
        }
    }

    private static DataInputStream input(byte[] bytes, int type) throws IOException {
        if (bytes == null || bytes.length < 9 || bytes.length > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw malformed("Frozen Legacy source bytes are outside Format-15 limits");
        }
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readInt() != type) {
            throw malformed("Unsupported frozen Legacy source codec header");
        }
        return in;
    }

    private static void header(DataOutputStream out, int type) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(type);
    }

    private static void samplingParameters(DataOutputStream out,
            TileHeatmapSampler.SamplingParameters parameters) throws IOException {
        validateSamplingParameters(parameters);
        out.writeInt(parameters.zoom());
        out.writeDouble(parameters.latitude());
        out.writeDouble(parameters.metersPerPixel());
        out.writeDouble(parameters.halfWidthMeters());
        out.writeDouble(parameters.sampleStepMeters());
        out.writeInt(parameters.halfWidthPx());
        out.writeInt(parameters.stepPx());
    }

    private static TileHeatmapSampler.SamplingParameters samplingParameters(DataInputStream in) throws IOException {
        var parameters = new TileHeatmapSampler.SamplingParameters(in.readInt(), finite(in.readDouble()),
                positive(in.readDouble()), positive(in.readDouble()), positive(in.readDouble()),
                in.readInt(), in.readInt());
        validateSamplingParameters(parameters);
        return parameters;
    }

    private static void validateSamplingParameters(TileHeatmapSampler.SamplingParameters parameters) {
        if (parameters.zoom() < 0 || parameters.zoom() > 30
                || !Double.isFinite(parameters.latitude()) || Math.abs(parameters.latitude()) > 85.05112878
                || !Double.isFinite(parameters.metersPerPixel()) || parameters.metersPerPixel() <= 0
                || !Double.isFinite(parameters.halfWidthMeters()) || parameters.halfWidthMeters() <= 0
                || !Double.isFinite(parameters.sampleStepMeters()) || parameters.sampleStepMeters() <= 0
                || parameters.halfWidthPx() <= 0 || parameters.stepPx() <= 0
                || parameters.stepPx() > parameters.halfWidthPx()) {
            throw malformed("Managed sampling parameters are invalid");
        }
    }

    private static void compressedPixels(DataOutputStream out, int[] argb, int width, int height) throws IOException {
        if ((long) width * height != argb.length) throw malformed("Raster pixel count does not match dimensions");
        var compressed = new BoundedOutput();
        try (DataOutputStream pixels = new DataOutputStream(new DeflaterOutputStream(compressed))) {
            for (int value : argb) pixels.writeInt(value);
        }
        byte[] bytes = compressed.toByteArray();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static int[] readPixels(DataInputStream in, int width, int height) throws IOException {
        int count = pixelsBound(width, height, 1);
        int compressedLength = in.readInt();
        if (compressedLength <= 0 || compressedLength > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw malformed("Compressed raster is outside artifact limits");
        }
        byte[] compressed = new byte[compressedLength];
        in.readFully(compressed);
        int[] result = new int[count];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] block = new byte[8192];
            int pixelIndex = 0;
            int word = 0;
            int wordBytes = 0;
            long outputBytes = 0;
            while (!inflater.finished()) {
                int length = inflater.inflate(block);
                if (length == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        throw malformed("Compressed raster is truncated");
                    }
                    throw malformed("Compressed raster made no progress");
                }
                outputBytes += length;
                if (outputBytes > (long) count * Integer.BYTES) {
                    throw malformed("Compressed raster expands beyond its declared dimensions");
                }
                for (int index = 0; index < length; index++) {
                    word = (word << 8) | (block[index] & 0xFF);
                    if (++wordBytes == Integer.BYTES) {
                        result[pixelIndex++] = word;
                        word = 0;
                        wordBytes = 0;
                    }
                }
            }
            if (pixelIndex != count || wordBytes != 0 || inflater.getRemaining() != 0) {
                throw malformed("Compressed raster length or trailing data is invalid");
            }
            return result;
        } catch (DataFormatException failure) {
            throw malformed("Compressed raster is malformed", failure);
        } finally {
            inflater.end();
        }
    }

    private static long addRasterBytes(long total, int width, int height) {
        long bytes = (long) pixelsBound(width, height, 1) * Integer.BYTES;
        if (total > MAX_RETAINED_RASTER_BYTES - bytes) {
            throw malformed("Frozen managed rasters exceed retained-memory limit");
        }
        return total + bytes;
    }

    private static int pixelsBound(int width, int height, int multiplier) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height * multiplier * Integer.BYTES > MAX_RETAINED_RASTER_BYTES) {
            throw malformed("Raster dimensions exceed frozen-source limits");
        }
        return Math.multiplyExact(width, height);
    }

    private static void strings(DataOutputStream out, List<String> strings, int max, boolean unique)
            throws IOException {
        count(out, strings.size(), max);
        var seen = new java.util.HashSet<String>();
        for (String value : strings) {
            safeString(out, value);
            if (unique && !seen.add(value)) throw malformed("String list contains duplicates");
        }
    }

    private static List<String> strings(DataInputStream in, int max, boolean unique) throws IOException {
        int count = count(in, max);
        var values = new ArrayList<String>(count);
        var seen = new java.util.HashSet<String>();
        for (int index = 0; index < count; index++) {
            String value = safeString(in);
            if (unique && !seen.add(value)) throw malformed("String list contains duplicates");
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static void safeString(DataOutputStream out, String value) throws IOException {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_BYTES) {
            throw malformed("Frozen Legacy text value is missing or too large");
        }
        Format15Safety.requireSafeExportedMetadata(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw malformed("Frozen Legacy text value is too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String safeString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > MAX_TEXT_BYTES) throw malformed("Frozen Legacy text length is invalid");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Format15Safety.requireSafeExportedMetadata(value);
            if (value.isBlank()) throw malformed("Frozen Legacy text value is blank");
            return value;
        } catch (CharacterCodingException failure) {
            throw malformed("Frozen Legacy text is not UTF-8", failure);
        }
    }

    private static void enumName(DataOutputStream out, Enum<?> value) throws IOException {
        safeString(out, value.name());
    }

    private static <T extends Enum<T>> T enumValue(DataInputStream in, Class<T> type) throws IOException {
        String name = safeString(in);
        try { return Enum.valueOf(type, name); }
        catch (IllegalArgumentException failure) { throw malformed("Unknown frozen Legacy enum value", failure); }
    }

    private static void count(DataOutputStream out, int count, int max) throws IOException {
        if (count < 0 || count > max) throw malformed("Frozen Legacy collection exceeds limits");
        out.writeInt(count);
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw malformed("Frozen Legacy collection exceeds limits");
        return count;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw malformed("Frozen Legacy number is not finite");
        return value;
    }

    private static double positive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) throw malformed("Frozen Legacy number is not positive");
        return value;
    }

    private static double nonnegative(double value) {
        if (!Double.isFinite(value) || value < 0.0) throw malformed("Frozen Legacy number is negative or non-finite");
        return value;
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.read() != -1) throw malformed("Frozen Legacy source has trailing bytes");
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException malformed(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            requireCapacity(length);
            super.write(bytes, offset, length);
        }

        private void requireCapacity(int addition) {
            if (addition < 0 || count > Format15Safety.MAX_ARTIFACT_BYTES - addition) {
                throw malformed("Frozen Legacy source exceeds the Format-15 artifact limit");
            }
        }
    }
}
