package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch;

/** Ordered bounded snapshots for the actual interval outputs already retained by one batch. */
final class IntervalFinalOutputComponentsCodec {
    static final String ARTIFACT = "private/interval-output-components.bin";
    private static final int MAGIC = 0x57484943; // WHIC
    private static final int VERSION = 1;
    private static final int MAX_INTERVALS = 128;

    enum Availability { AVAILABLE, UNAVAILABLE, UNAVAILABLE_BUDGET }
    record Encoded(Availability availability, byte[] bytes, List<String> summaries) {
        Encoded { bytes = bytes == null ? null : bytes.clone(); summaries = List.copyOf(summaries); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
    record Decoded(List<FinalOutputComponentsCodec.Snapshot> snapshots) {
        Decoded { snapshots = List.copyOf(snapshots); }
    }

    private IntervalFinalOutputComponentsCodec() { }

    static Encoded encode(String buildIdentity, IntervalTraceBatch batch) {
        return encode(buildIdentity, batch, Format15Safety.MAX_ARTIFACT_BYTES, 0);
    }

    static Encoded encode(String buildIdentity, IntervalTraceBatch batch,
            int maximumBytes, long alreadyRetainedBytes) {
        if (buildIdentity == null || batch == null || batch.runs().size() > MAX_INTERVALS) {
            throw new IllegalArgumentException("interval-components-input-invalid");
        }
        Format15Safety.requireSafeExportedMetadata(buildIdentity);
        // Privacy/invalid metadata is never converted into budget-only omission,
        // including a later run when prior results already exhaust shared admission.
        for (var run : batch.runs()) {
            FinalOutputComponentsCodec.validateOutputMetadata(run.result().inference(), run.result().routes());
        }
        try {
            if (maximumBytes < 0 || maximumBytes > Format15Safety.MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("snapshot-byte-budget-invalid");
            }
            ReplayOutputAdmission.Budget budget = new ReplayOutputAdmission.Budget(alreadyRetainedBytes);
            // All runs share admission, including prior retained results; no per-run reset.
            for (var run : batch.runs()) budget.output(run.result().inference(), run.result().routes());
            List<FinalOutputComponentsCodec.Prepared> prepared = new ArrayList<>(batch.runs().size());
            for (var run : batch.runs()) {
                String requestHash = FinalOutputComponentsCodec.requestHash(run.request());
                Format15ReplayRunner.Result result = result(run, requestHash);
                var binding = new FinalOutputComponentsCodec.Binding(buildIdentity, requestHash,
                        run.request().parameterHash(), run.request().engine(), run.request().engine(),
                        ReplayLevel.FINAL_GEOMETRY, FinalReplayFingerprint.SCHEMA_VERSION,
                        FinalReplayFingerprint.sha256(result));
                prepared.add(FinalOutputComponentsCodec.prepare(binding, result, maximumBytes));
            }
            BoundedOutputStream measured = new BoundedOutputStream(OutputStream.nullOutputStream(), maximumBytes);
            try (DataOutputStream data = new DataOutputStream(measured)) { writePrepared(data, buildIdentity, prepared); }
            budget.payload(measured.written, 6);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(measured.written);
            try (DataOutputStream data = new DataOutputStream(new BoundedOutputStream(bytes, measured.written))) {
                writePrepared(data, buildIdentity, prepared);
            }
            List<String> summaries = prepared.stream().map(one ->
                    FinalOutputComponentsCodec.summaryJson(one.summary()).stripTrailing()).toList();
            return new Encoded(Availability.AVAILABLE, bytes.toByteArray(), summaries);
        } catch (SnapshotBudgetExceeded | FinalOutputComponentsCodec.SnapshotBudgetExceeded tooLarge) {
            return new Encoded(Availability.UNAVAILABLE_BUDGET, null, List.of());
        } catch (IOException failure) {
            throw new IllegalStateException("interval-components-write-failed", failure);
        } catch (IllegalArgumentException invalid) {
            if (ReplayOutputAdmission.isBudget(invalid)) {
                return new Encoded(Availability.UNAVAILABLE_BUDGET, null, List.of());
            }
            throw invalid;
        }
    }

    private static void writePrepared(DataOutputStream data, String buildIdentity,
            List<FinalOutputComponentsCodec.Prepared> prepared) throws IOException {
        data.writeInt(MAGIC); data.writeInt(VERSION); writeString(data, buildIdentity); data.writeInt(prepared.size());
        for (int index = 0; index < prepared.size(); index++) {
            var one = prepared.get(index);
            data.writeInt(index);
            data.write(one.binding().inputHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            data.writeInt(one.byteCount());
            FinalOutputComponentsCodec.writeSnapshot(data, one); // No embedded payload allocation/copy.
        }
    }

    static Decoded decode(byte[] payload, String buildIdentity,
            FrozenIntervalReplayCodec.Payload frozen) {
        if (payload == null || payload.length > Format15Safety.MAX_ARTIFACT_BYTES
                || buildIdentity == null || frozen == null) {
            throw new ReplayMismatchException("interval-components-invalid");
        }
        try {
            ReplayOutputAdmission.Budget budget = new ReplayOutputAdmission.Budget(0);
            budget.payload(payload.length, 3);
            byte[] sealed = payload.clone();
            List<FinalOutputComponentsCodec.Admitted> admitted = new ArrayList<>(MAX_INTERVALS);
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(sealed))) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION
                    || !readString(data).equals(buildIdentity)) throw new IllegalArgumentException("header");
            int count = data.readInt();
            if (count < 1 || count > MAX_INTERVALS || count != frozen.runs().size()) {
                throw new IllegalArgumentException("count");
            }
            for (int index = 0; index < count; index++) {
                if (data.readInt() != index) throw new IllegalArgumentException("order");
                String requestHash = readHash(data);
                var expected = frozen.runs().get(index);
                String expectedRequestHash = FinalOutputComponentsCodec.requestHash(expected.request());
                if (!requestHash.equals(expectedRequestHash)) throw new IllegalArgumentException("request-binding");
                int length = data.readInt();
                if (length < 0 || length > Format15Safety.MAX_ARTIFACT_BYTES) {
                    throw new IllegalArgumentException("snapshot-length");
                }
                int offset = sealed.length - data.available();
                if (length > data.available()) throw new IllegalArgumentException("snapshot-truncated");
                FinalOutputComponentsCodec.Binding binding = new FinalOutputComponentsCodec.Binding(
                        buildIdentity, expectedRequestHash, frozen.shared().request().parameterHash(),
                        expected.request().engine(), expected.request().engine(), ReplayLevel.FINAL_GEOMETRY,
                        FinalReplayFingerprint.SCHEMA_VERSION, expected.finalHash());
                admitted.add(FinalOutputComponentsCodec.preflight(sealed, offset, length, binding, budget));
                data.skipNBytes(length);
            }
            if (data.read() != -1) throw new IllegalArgumentException("trailing");
            }
            // Only after the complete shared admission succeeds may any typed result exist.
            List<FinalOutputComponentsCodec.Snapshot> snapshots = new ArrayList<>(admitted.size());
            for (var one : admitted) snapshots.add(FinalOutputComponentsCodec.materialize(one));
            return new Decoded(snapshots);
        } catch (ReplayMismatchException mismatch) {
            throw mismatch;
        } catch (IOException | RuntimeException malformed) {
            throw new ReplayMismatchException("interval-components-invalid");
        }
    }

    static Format15ReplayRunner.Result result(IntervalTraceBatch.IntervalRun run,
            String requestHash) {
        return new Format15ReplayRunner.Result(ReplayLevel.FINAL_GEOMETRY,
                run.request().engine(), run.request().engine(), run.result().inference(),
                run.result().routes(), requestHash);
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        ScalarReplayFingerprint.writeString(data, value);
    }

    private static String readString(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > 1_048_576) throw new IllegalArgumentException("string-length");
        byte[] bytes = data.readNBytes(length);
        if (bytes.length != length) throw new IllegalArgumentException("string-truncated");
        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Format15Safety.requireSafeExportedMetadata(value);
        return value;
    }

    private static String readHash(DataInputStream data) throws IOException {
        byte[] bytes = data.readNBytes(64);
        if (bytes.length != 64) throw new IllegalArgumentException("request-hash-truncated");
        String value = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        return Format15Safety.requiredHash(value, "intervalRequestHash");
    }

    private static final class SnapshotBudgetExceeded extends IOException { }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final int maximum;
        private int written;
        BoundedOutputStream(OutputStream delegate, int maximum) { super(delegate); this.maximum = maximum; }
        @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length); out.write(bytes, offset, length);
        }
        private void reserve(int length) throws SnapshotBudgetExceeded {
            if (length < 0 || (long) written + length > maximum) throw new SnapshotBudgetExceeded();
            written += length;
        }
    }
}
