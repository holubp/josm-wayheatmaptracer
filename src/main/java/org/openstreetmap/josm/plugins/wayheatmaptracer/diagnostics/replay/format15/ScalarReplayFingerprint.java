package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;

/** Canonical, bounded scalar replay output fingerprint. */
final class ScalarReplayFingerprint {
    static final int SCHEMA_VERSION = 1;
    private static final int MAX_HYPOTHESES = 4_096;
    private static final int MAX_AGGREGATE_ITEMS = 500_000;
    private static final int MAX_STRING_BYTES = 1_048_576;

    private ScalarReplayFingerprint() {
    }

    /** Hashes authoritative scalar semantics without retaining another serialized output copy. */
    static String sha256(TraceHypothesisSet output) {
        if (output == null || output.hypotheses().size() > MAX_HYPOTHESES) {
            throw new IllegalArgumentException("scalar-output-budget");
        }
        long items = output.hypotheses().size();
        for (TraceHypothesis hypothesis : output.hypotheses()) {
            items = Math.addExact(items, hypothesis.points().size());
            items = Math.addExact(items, hypothesis.support().size());
            items = Math.addExact(items, hypothesis.diagnostics().size());
            if (items > MAX_AGGREGATE_ITEMS) {
                throw new IllegalArgumentException("scalar-output-budget");
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream data = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                data.writeInt(SCHEMA_VERSION);
                writeString(data, output.engine().name());
                writeString(data, output.status().name());
                data.writeBoolean(output.alternativesTruncated());
                data.writeLong(output.evaluatedStates());
                data.writeLong(output.evaluatedTransitions());
                data.writeInt(output.hypotheses().size());
                for (TraceHypothesis hypothesis : output.hypotheses()) {
                    writeHypothesis(data, hypothesis);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Scalar digest stream failed", exception);
        }
    }

    static void writeHypothesis(DataOutputStream data, TraceHypothesis hypothesis)
            throws IOException {
        ReplayOutputCanonical.writeHypothesis(data, hypothesis);
    }

    static void writeString(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("scalar-output-invalid");
        }
        if (value.length() > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("scalar-output-budget");
        }
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("scalar-output-invalid", exception);
        }
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("scalar-output-budget");
        }
        data.writeInt(bytes.length);
        data.write(bytes);
    }
}
