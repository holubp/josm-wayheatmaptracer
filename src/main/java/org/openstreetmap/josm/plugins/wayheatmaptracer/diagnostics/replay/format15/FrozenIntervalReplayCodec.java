package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;

/** Bounded interval envelope with explicit legacy or complete numerical-frame authority. */
final class FrozenIntervalReplayCodec {
    static final String ARTIFACT = "private/frozen-interval-input.bin";
    private static final int MAGIC = 0x57544952;
    private static final int VERSION = 3;
    private static final int MAX_INTERVALS = 128;

    record ExpectedRun(TraceRequest request, String scalarHash, String finalHash,
            List<String> routeIdentities, int chosenRouteIndex,
            FixedIntervalEditPlanComposer.Disposition disposition,
            Format15ProductionBundleFactory.IntervalReason reason) {
        ExpectedRun {
            routeIdentities = List.copyOf(routeIdentities);
        }
    }

    record Payload(FrozenReplayInput shared, String sharedInputHash,
            NetworkSnapshotCapture.Specification authority, String partitionProofHash,
            List<ExpectedRun> runs, String previewHash, String planHash) {
        Payload {
            runs = List.copyOf(runs);
        }
    }

    private FrozenIntervalReplayCodec() { }

    record Encoded(byte[] bytes, String sharedInputHash) { }

    static byte[] encode(IntervalTraceBatch batch,
            FixedIntervalEditPlanComposer.Assessment assessment, Map<Integer, Integer> choices,
            String previewHash) {
        return encodeWithIdentity(batch, assessment, choices, previewHash).bytes();
    }

    static Encoded encodeWithIdentity(IntervalTraceBatch batch,
            FixedIntervalEditPlanComposer.Assessment assessment, Map<Integer, Integer> choices,
            String previewHash) {
        if (batch.authoritySpecification() == null || batch.runs().isEmpty()
                || batch.runs().size() > MAX_INTERVALS) {
            throw new IllegalArgumentException("Strict interval replay requires bounded original authority");
        }
        try {
            ByteArrayOutputStream bytes = new BoundedOutput();
            DataOutputStream out = new DataOutputStream(bytes);
            boolean completeFrame = batch.evidence().coordinateFrame().hasCompleteNumericalIdentity();
            if (completeFrame != batch.authoritySpecification().metricFrame().hasCompleteNumericalIdentity()) {
                throw new IllegalArgumentException("Interval authority mixes numerical frame versions");
            }
            out.writeInt(MAGIC);
            out.writeInt(completeFrame ? VERSION : 2);
            String sharedHash = partWithHash(out, FrozenReplayCodec.encode(new FrozenReplayInput(batch.fullRequest(),
                    batch.evidence(), batch.network(), batch.options())));
            part(out, FrozenReplayCodec.encodeAuthority(batch.authoritySpecification()));
            hash(out, FrozenReplayCodec.partitionProofHash(batch.partition()));
            out.writeInt(batch.runs().size());
            for (int index = 0; index < batch.runs().size(); index++) {
                var run = batch.runs().get(index);
                var expected = assessment.intervals().get(index);
                byte[] request = FrozenReplayCodec.encodeRequestOnly(run.request());
                part(out, request);
                out.writeDouble(run.request().profileChainage().sourceOriginGroundMeters());
                var result = new Format15ReplayRunner.Result(ReplayLevel.FINAL_GEOMETRY,
                        run.request().engine(), run.request().engine(),
                        run.result().inference(), run.result().routes(),
                        Format15Safety.sha256(request));
                hash(out, ScalarReplayFingerprint.sha256(run.result().inference()));
                hash(out, FinalReplayFingerprint.sha256(result));
                out.writeInt(run.routes().size());
                for (var route : run.routes()) safeText(out, route.hypothesis().id());
                out.writeInt(choices.getOrDefault(index, 0));
                out.writeInt(expected.disposition().ordinal());
                out.writeInt(Format15ProductionBundleFactory.intervalReason(expected.reason()).ordinal());
                if (bytes.size() > Format15Safety.MAX_ARTIFACT_BYTES) {
                    throw new IllegalArgumentException("Strict interval replay exceeds per-file budget");
                }
            }
            hash(out, previewHash);
            out.writeBoolean(assessment.plan().isPresent());
            if (assessment.plan().isPresent()) hash(out, assessment.plan().orElseThrow().canonicalHash());
            out.flush();
            if (bytes.size() > Format15Safety.MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("Strict interval replay exceeds per-file budget");
            }
            return new Encoded(bytes.toByteArray(), sharedHash);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Strict interval replay could not be encoded", failure);
        }
    }

    static Payload decode(byte[] bytes) {
        if (bytes == null || bytes.length > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Strict interval replay exceeds per-file budget");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("Unsupported interval replay codec version");
            }
            int version = in.readInt();
            if (version != 2 && version != VERSION) {
                throw new IllegalArgumentException("Unsupported interval replay codec version");
            }
            byte[] sharedWire = part(in);
            String sharedHash = Format15Safety.sha256(sharedWire);
            FrozenReplayInput shared = FrozenReplayCodec.decode(sharedWire);
            sharedWire = null;
            NetworkSnapshotCapture.Specification authority = FrozenReplayCodec.decodeAuthority(part(in));
            if ((version == VERSION) != shared.evidence().coordinateFrame().hasCompleteNumericalIdentity()
                    || (version == VERSION) != authority.metricFrame().hasCompleteNumericalIdentity()) {
                throw new IllegalArgumentException("Interval envelope disagrees with numerical frame versions");
            }
            String proof = hash(in);
            int count = in.readInt();
            if (count < 1 || count > MAX_INTERVALS) {
                throw new IllegalArgumentException("Interval replay count exceeds budget");
            }
            List<ExpectedRun> runs = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                TraceRequest request = withSourceOrigin(
                        FrozenReplayCodec.decodeRequestOnly(part(in), shared.evidence()),
                        in.readDouble());
                String scalar = hash(in), finalHash = hash(in);
                int routes = in.readInt();
                if (routes < 0 || routes > IntervalTraceBatch.MAX_RETAINED_ROUTES) {
                    throw new IllegalArgumentException("Interval route count exceeds budget");
                }
                List<String> routeIds = new ArrayList<>(routes);
                for (int route = 0; route < routes; route++) routeIds.add(safeText(in));
                int chosen = in.readInt();
                int disposition = in.readInt(), reason = in.readInt();
                if (chosen < 0 || routes > 0 && chosen >= routes || routes == 0 && chosen != 0
                        || disposition < 0 || disposition >= FixedIntervalEditPlanComposer.Disposition.values().length
                        || reason < 0 || reason >= Format15ProductionBundleFactory.IntervalReason.values().length) {
                    throw new IllegalArgumentException("Interval replay choice or disposition is invalid");
                }
                runs.add(new ExpectedRun(request, scalar, finalHash, routeIds, chosen,
                        FixedIntervalEditPlanComposer.Disposition.values()[disposition],
                        Format15ProductionBundleFactory.IntervalReason.values()[reason]));
            }
            String preview = hash(in);
            String plan = in.readBoolean() ? hash(in) : null;
            if (in.read() != -1) throw new IllegalArgumentException("Interval replay has trailing bytes");
            return new Payload(shared, sharedHash, authority, proof, runs, preview, plan);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed strict interval replay input", failure);
        }
    }

    /** Hashes the already encoded/admitted bytes while writing them, without another encoding. */
    private static String partWithHash(DataOutputStream out, byte[] value) throws IOException {
        part(out, value);
        return Format15Safety.sha256(value);
    }

    private static void part(DataOutputStream out, byte[] value) throws IOException {
        if (value.length > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Interval replay component exceeds budget");
        }
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] part(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > Format15Safety.MAX_ARTIFACT_BYTES || length > in.available()) {
            throw new IllegalArgumentException("Interval replay component exceeds budget");
        }
        return in.readNBytes(length);
    }

    private static TraceRequest withSourceOrigin(TraceRequest source, double origin) {
        return new TraceRequest(source.selectedWayKey(), source.selectedRange(), source.engine(),
                source.geometryMode(), source.permissions(), source.budgets(),
                source.evidenceSnapshotId(), source.evidenceContentHash(),
                source.networkSnapshotId(), source.networkContentHash(), source.settingsHash(),
                source.parameterHash(), source.samplerId(), source.configuredSampleStepMeters(),
                source.profileChainage().withSourceOrigin(origin), source.evidenceResolution(),
                source.corridorInput());
    }

    private static void hash(DataOutputStream out, String value) throws IOException {
        out.writeUTF(Format15Safety.requiredHash(value, "interval replay hash"));
    }

    private static String hash(DataInputStream in) throws IOException {
        return Format15Safety.requiredHash(in.readUTF(), "interval replay hash");
    }

    private static void safeText(DataOutputStream out, String value) throws IOException {
        Format15Safety.requireSafeExportedMetadata(value);
        out.writeUTF(value);
    }

    private static String safeText(DataInputStream in) throws IOException {
        String value = in.readUTF();
        Format15Safety.requireSafeExportedMetadata(value);
        return value;
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        BoundedOutput() {
            super(8192);
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] source, int offset, int length) {
            requireCapacity(length);
            super.write(source, offset, length);
        }

        private void requireCapacity(int additional) {
            if (additional < 0 || count > Format15Safety.MAX_ARTIFACT_BYTES - additional) {
                throw new IllegalArgumentException("Strict interval replay exceeds per-file budget");
            }
        }
    }
}
