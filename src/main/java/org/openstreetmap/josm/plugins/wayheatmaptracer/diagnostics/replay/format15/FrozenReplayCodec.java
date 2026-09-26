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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExternalPort;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterResamplingProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterTransformCertificate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/**
 * Explicit bounded binary codec for production-replay inputs. No Java
 * serialization is used.
 */
public final class FrozenReplayCodec {
    public static final int VERSION = 2;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TEXT = 1_000_000;
    private static final int MAX_ENTRIES = 250_000;
    private static final long MAX_AGGREGATE_ENTRIES = 500_000;
    private static final long MAX_RETAINED_BYTES = 64L * 1024L * 1024L;
    private static final ThreadLocal<Admission> DECODE_ADMISSION = new ThreadLocal<>();

    private FrozenReplayCodec() {}

    /**
     * Encodes every frozen engine input under a versioned, length-bounded
     * envelope.
     */
    public static byte[] encode(FrozenReplayInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Frozen replay input is required");
        }
        admit(input, new Admission());
        try {
            BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(
                Format15Safety.MAX_ARTIFACT_BYTES);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(0x57545250);
                out.writeInt(VERSION);
                evidence(out, input.evidence());
                request(out, input.request());
                network(out, input.network());
                options(out, input.options());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Frozen replay input exceeds byte budget", exception);
        }
    }

    /**
     * Decodes through production validating constructors and rejects trailing or
     * mixed values.
     */
    public static FrozenReplayInput decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Frozen replay bytes are outside budget");
        }
        Admission admission = new Admission();
        DECODE_ADMISSION.set(admission);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x57545250) {
                throw new IllegalArgumentException("Unsupported frozen replay codec version");
            }
            int version = in.readInt();
            if (version != 1 && version != VERSION) {
                throw new IllegalArgumentException("Unsupported frozen replay codec version");
            }
            EvidenceSnapshot evidence = evidence(in, version);
            TraceRequest request = request(in, evidence.coordinateFrame(), evidence.transform());
            NetworkSnapshot network = network(in);
            ModernTracePipeline.Options options = options(in);
            if (in.read() != -1) {
                throw new IllegalArgumentException("Frozen replay input has trailing data");
            }
            FrozenReplayInput decoded = new FrozenReplayInput(request, evidence, network, options);
            admit(decoded, new Admission());
            return decoded;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Malformed frozen replay input", exception);
        } finally {
            DECODE_ADMISSION.remove();
        }
    }

    /** Encodes one interval request without copying its shared evidence or network. */
    static byte[] encodeRequestOnly(TraceRequest value) {
        try {
            BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(
                    Format15Safety.MAX_ARTIFACT_BYTES);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                request(out, value);
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Interval request exceeds budget", failure);
        }
    }

    /** Decodes one request against the sole shared evidence frame. */
    static TraceRequest decodeRequestOnly(byte[] bytes, EvidenceSnapshot evidence) {
        if (bytes == null || bytes.length > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Interval request exceeds budget");
        }
        DECODE_ADMISSION.set(new Admission());
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            TraceRequest result = request(in, evidence.coordinateFrame(), evidence.transform());
            if (in.read() != -1) throw new IllegalArgumentException("Interval request has trailing bytes");
            return result;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed interval request", failure);
        } finally {
            DECODE_ADMISSION.remove();
        }
    }

    /** Version-one original partition authority, kept separate from the v1/v2 single-request codec. */
    static byte[] encodeAuthority(NetworkSnapshotCapture.Specification s) {
        try {
            BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(
                    Format15Safety.MAX_ARTIFACT_BYTES);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                str(out, s.snapshotId());
                str(out, s.datasetIdentity());
                out.writeLong(s.sourceGeneration());
                key(out, s.selectedWayKey());
                range(out, s.selectedRange());
                frame(out, s.metricFrame());
                region(out, s.collisionEnvelope());
                region(out, s.editRegion());
                count(out, s.editableWayOccurrences().size());
                for (PrimitiveKey way : orderedKeys(s.editableWayOccurrences().keySet())) {
                    key(out, way);
                    count(out, s.editableWayOccurrences().get(way).size());
                    for (OccurrenceRange occurrence : s.editableWayOccurrences().get(way))
                        range(out, occurrence);
                }
                keys(out, s.editableExistingKeys());
                keys(out, s.movableExistingNodeKeys());
                keys(out, s.removableExistingNodeKeys());
                keys(out, s.explicitlyProtectedNodeKeys());
                out.writeBoolean(s.mayCreateNodes());
                out.writeBoolean(s.permissions().widerDiscovery());
                out.writeDouble(s.permissions().ordinaryRadiusMeters());
                out.writeDouble(s.permissions().maximumDiscoveryRadiusMeters());
                en(out, s.permissions().junctionPolicy());
                out.writeBoolean(s.permissions().reconstructIncidentWays());
                count(out, s.readOnlyPorts().size());
                for (ExternalPort port : s.readOnlyPorts()) port(out, port);
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Partition authority exceeds budget", failure);
        }
    }

    static NetworkSnapshotCapture.Specification decodeAuthority(byte[] bytes) {
        if (bytes == null || bytes.length > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Partition authority exceeds budget");
        }
        DECODE_ADMISSION.set(new Admission());
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String id = str(in), dataset = str(in);
            long generation = in.readLong();
            PrimitiveKey selected = key(in);
            OccurrenceRange selection = range(in);
            LocalMetricFrame metric = frame(in);
            MetricRegion collision = region(in), edit = region(in);
            int n = count(in);
            Map<PrimitiveKey, List<OccurrenceRange>> occurrences = new LinkedHashMap<>();
            for (int index = 0; index < n; index++) {
                PrimitiveKey way = key(in);
                int size = count(in);
                List<OccurrenceRange> ranges = new ArrayList<>(size);
                for (int j = 0; j < size; j++) ranges.add(range(in));
                if (occurrences.put(way, ranges) != null)
                    throw new IllegalArgumentException("Duplicate authority way");
            }
            Set<PrimitiveKey> editable = keys(in), movable = keys(in), removable = keys(in),
                    protectedKeys = keys(in);
            boolean create = in.readBoolean();
            RecoveryPermissions permissions = new RecoveryPermissions(in.readBoolean(),
                    in.readDouble(), in.readDouble(), en(in, JunctionPolicy.class),
                    in.readBoolean());
            n = count(in);
            List<ExternalPort> ports = new ArrayList<>(n);
            for (int index = 0; index < n; index++) ports.add(port(in));
            if (in.read() != -1) throw new IllegalArgumentException("Partition authority has trailing bytes");
            return new NetworkSnapshotCapture.Specification(id, dataset, generation, selected,
                    selection, metric, collision, edit, occurrences, editable, movable,
                    removable, protectedKeys, create, permissions, ports);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed partition authority", failure);
        } finally {
            DECODE_ADMISSION.remove();
        }
    }

    /** Hashes the complete typed partition proof, with captured network parity bound separately. */
    static String partitionProofHash(SelectedWayIntervalPartitioner.Partition p) {
        try {
            BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(
                    Format15Safety.MAX_ARTIFACT_BYTES);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                key(out, p.selectedWayKey());
                range(out, p.selectedRange());
                str(out, p.datasetIdentity());
                out.writeLong(p.sourceGeneration());
                count(out, p.fixedIslands().size());
                for (var island : p.fixedIslands()) {
                    range(out, island.range());
                    count(out, island.occurrenceKeys().size());
                    for (PrimitiveKey k : island.occurrenceKeys()) key(out, k);
                    keys(out, island.junctionKeys());
                    count(out, island.reasons().size());
                    for (var reason : island.reasons()) en(out, reason);
                    boundary(out, island.beforeBoundary());
                    boundary(out, island.afterBoundary());
                    keys(out, island.provedPrimitiveKeys());
                    ports(out, island.provedPorts());
                }
                count(out, p.slideIntervals().size());
                for (var interval : p.slideIntervals()) {
                    range(out, interval.range());
                    count(out, interval.occurrenceKeys().size());
                    for (PrimitiveKey k : interval.occurrenceKeys()) key(out, k);
                    boundary(out, interval.startBoundary());
                    boundary(out, interval.endBoundary());
                }
                count(out, p.junctionDispositions().size());
                for (var disposition : p.junctionDispositions()) {
                    out.writeInt(disposition.selectedOccurrenceIndex());
                    key(out, disposition.nodeKey());
                    en(out, disposition.reason());
                    out.writeBoolean(disposition.automaticEligible());
                    out.writeBoolean(disposition.provedFootprint() != null);
                    if (disposition.provedFootprint() != null) range(out, disposition.provedFootprint());
                }
                ports(out, p.provedPorts());
            }
            return Format15Safety.sha256(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalArgumentException("Partition proof exceeds budget", failure);
        }
    }

    private static void boundary(DataOutputStream out,
            SelectedWayIntervalPartitioner.BoundaryConstraint b) throws IOException {
        en(out, b.kind());
        out.writeInt(b.occurrenceIndex());
        key(out, b.nodeKey());
        out.writeBoolean(b.mayMove());
        out.writeBoolean(b.requiresJointPlan());
        en(out, b.reason());
    }

    private static void ports(DataOutputStream out, Set<ExternalPort> values) throws IOException {
        count(out, values.size());
        for (ExternalPort value : values.stream().sorted(Comparator.comparing(ExternalPort::toString)).toList())
            port(out, value);
    }

    private static void port(DataOutputStream out, ExternalPort p) throws IOException {
        key(out, p.wayKey());
        key(out, p.boundaryNodeKey());
        key(out, p.outsideNeighborKey());
        out.writeInt(p.boundaryOccurrenceIndex());
        en(out, p.side());
        geo(out, p.outsideNeighborCoordinate());
    }

    private static ExternalPort port(DataInputStream in) throws IOException {
        return new ExternalPort(key(in), key(in), key(in), in.readInt(),
                en(in, ExternalPort.Side.class), geo(in));
    }

    /** Encodes the exact immutable reviewed edit plan in a separate bounded artifact. */
    public static byte[] encodeEditPlan(AlignmentEditPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Edit plan is required");
        }
        try {
            BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(
                Format15Safety.MAX_ARTIFACT_BYTES);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(0x57544550);
                out.writeInt(1);
                key(out, plan.selectedWayKey());
                range(out, plan.selectedRange());
                network(out, plan.before());
                network(out, plan.after());
                frame(out, plan.metricFrame());
                RecoveryPermissions permissions = plan.permissions();
                out.writeBoolean(permissions.widerDiscovery());
                out.writeDouble(permissions.ordinaryRadiusMeters());
                out.writeDouble(permissions.maximumDiscoveryRadiusMeters());
                en(out, permissions.junctionPolicy());
                out.writeBoolean(permissions.reconstructIncidentWays());
                str(out, plan.settingsHash());
                str(out, plan.evidenceHash());
                str(out, plan.parameterHash());
                str(out, plan.routeIdentity());
                count(out, plan.finalPreviewWays().size());
                for (PrimitiveKey way : orderedKeys(plan.finalPreviewWays().keySet())) {
                    key(out, way);
                    List<GeographicPoint> points = plan.finalPreviewWays().get(way);
                    count(out, points.size());
                    for (GeographicPoint point : points) geo(out, point);
                }
                en(out, plan.validation().disposition());
                strings(out, plan.validation().findingCodes());
                str(out, plan.canonicalHash());
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalArgumentException("Edit plan exceeds diagnostic budget", error);
        }
    }

    /** Decodes through the production plan constructor and verifies its canonical identity. */
    public static AlignmentEditPlan decodeEditPlan(byte[] bytes) {
        if (bytes == null || bytes.length < 8
                || bytes.length > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Edit plan artifact is outside budget");
        }
        DECODE_ADMISSION.set(new Admission());
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x57544550 || in.readInt() != 1) {
                throw new IllegalArgumentException("Unsupported edit plan codec version");
            }
            PrimitiveKey selectedWay = key(in);
            OccurrenceRange selectedRange = range(in);
            NetworkSnapshot before = network(in);
            NetworkSnapshot after = network(in);
            LocalMetricFrame metricFrame = frame(in);
            RecoveryPermissions permissions = new RecoveryPermissions(in.readBoolean(),
                in.readDouble(), in.readDouble(), en(in, JunctionPolicy.class), in.readBoolean());
            String settings = str(in), evidence = str(in), parameters = str(in), route = str(in);
            int wayCount = count(in);
            Map<PrimitiveKey, List<GeographicPoint>> previews = new LinkedHashMap<>();
            for (int index = 0; index < wayCount; index++) {
                PrimitiveKey way = key(in);
                int pointCount = count(in);
                List<GeographicPoint> points = new ArrayList<>(pointCount);
                for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
                    points.add(geo(in));
                }
                if (previews.put(way, List.copyOf(points)) != null) {
                    throw new IllegalArgumentException("Duplicate planned way");
                }
            }
            ValidationReport validation = new ValidationReport(
                en(in, ValidationReport.Disposition.class), strings(in));
            String expectedHash = Format15Safety.requiredHash(str(in), "planHash");
            if (in.read() != -1) {
                throw new IllegalArgumentException("Edit plan artifact has trailing data");
            }
            AlignmentEditPlan plan = new AlignmentEditPlan(selectedWay, selectedRange,
                before, after, metricFrame, permissions, settings, evidence,
                parameters, route, previews, validation);
            if (!expectedHash.equals(plan.canonicalHash())) {
                throw new IllegalArgumentException("Edit plan artifact identity mismatch");
            }
            return plan;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Malformed frozen edit plan", error);
        } finally {
            DECODE_ADMISSION.remove();
        }
    }

    private static void request(DataOutputStream out, TraceRequest r) throws IOException {
        key(out, r.selectedWayKey());
        range(out, r.selectedRange());
        en(out, r.engine());
        en(out, r.geometryMode());
        out.writeBoolean(r.permissions().widerDiscovery());
        out.writeDouble(r.permissions().ordinaryRadiusMeters());
        out.writeDouble(r.permissions().maximumDiscoveryRadiusMeters());
        en(out, r.permissions().junctionPolicy());
        out.writeBoolean(r.permissions().reconstructIncidentWays());
        out.writeInt(r.budgets().maximumStatesPerProfile());
        out.writeLong(r.budgets().maximumPairVisits());
        out.writeLong(r.budgets().maximumTransitions());
        out.writeInt(r.budgets().maximumRawAlternatives());
        out.writeInt(r.budgets().maximumDistinctAlternatives());
        str(out, r.evidenceSnapshotId());
        str(out, r.evidenceContentHash());
        str(out, r.networkSnapshotId());
        str(out, r.networkContentHash());
        str(out, r.settingsHash());
        str(out, r.parameterHash());
        str(out, r.samplerId());
        out.writeDouble(r.configuredSampleStepMeters());
        chainage(out, r.profileChainage());
        resolution(out, r.evidenceResolution());
        out.writeBoolean(r.corridorInput().isPresent());
        if (r.corridorInput().isPresent()) {
            CorridorTraceInput c = r.corridorInput().get();
            out.writeDouble(c.lateralStepMeters());
            count(out, c.profileLocations().size());
            for (DetachedProfileSamplingLocation l : c.profileLocations()) {
                geo(out, l.geographicPoint());
                out.writeDouble(l.cumulativeGroundDistanceMeters());
            }
        }
    }
    private static TraceRequest request(DataInputStream in, LocalMetricFrame frame,
            RasterMetricTransform transform) throws IOException {
        PrimitiveKey way = key(in);
        OccurrenceRange range = range(in);
        TrackerMode engine = en(in, TrackerMode.class);
        AlignmentMode mode = en(in, AlignmentMode.class);
        RecoveryPermissions permissions = new RecoveryPermissions(in.readBoolean(), in.readDouble(),
                in.readDouble(), en(in, JunctionPolicy.class), in.readBoolean());
        TraceBudgets budgets = new TraceBudgets(
                in.readInt(), in.readLong(), in.readLong(), in.readInt(), in.readInt());
        String evidenceId = str(in), evidenceHash = str(in), networkId = str(in),
               networkHash = str(in), settings = str(in), parameters = str(in), sampler = str(in);
        double step = in.readDouble();
        ProfileChainage chainage = chainage(in);
        EvidenceResolution resolution = resolution(in);
        Optional<CorridorTraceInput> corridor = Optional.empty();
        if (in.readBoolean()) {
            double lateral = in.readDouble();
            int n = count(in);
            List<DetachedProfileSamplingLocation> locations = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                locations.add(DetachedProfileSamplingLocation.at(
                        geo(in), frame, transform, in.readDouble()));
            }
            corridor = Optional.of(new CorridorTraceInput(locations, lateral));
        }
        return new TraceRequest(way, range, engine, mode, permissions, budgets, evidenceId,
                evidenceHash, networkId, networkHash, settings, parameters, sampler, step, chainage,
                resolution, corridor);
    }

    // The remaining sections intentionally serialize concrete values rather than
    // class names or callbacks.
    private static void evidence(DataOutputStream output, EvidenceSnapshot evidence)
            throws IOException {
        str(output, evidence.snapshotId());
        frame(output, evidence.coordinateFrame());
        transform(output, evidence.transform());
        resolution(output, evidence.resolution());
        region(output, evidence.decisionRegion());
        region(output, evidence.evidenceRegion());
        provenance(output, evidence.resampling());
        str(output, evidence.sourceIdentity());
        count(output, evidence.fields().size());
        for (Map.Entry<String, ScalarEvidenceField> entry :
                new TreeMap<>(evidence.fields()).entrySet()) {
            str(output, entry.getKey());
            ScalarEvidenceField field = entry.getValue();
            output.writeInt(field.width());
            output.writeInt(field.height());
            lineage(output, field.lineage());
            double[] values = field.copiedValues();
            boolean[] valid = field.copiedValidity();
            scalarTotal(output, values.length, 9);
            for (int start = 0; start < values.length; start += MAX_ENTRIES) {
                int end = Math.min(values.length, start + MAX_ENTRIES);
                count(output, end - start);
                for (int index = start; index < end; index++) {
                if (valid[index] && !Double.isFinite(values[index])) {
                    throw new IllegalArgumentException(
                            "Replay scalar evidence contains a nonfinite value");
                }
                output.writeBoolean(valid[index]);
                output.writeDouble(values[index]);
                }
            }
            boolean[] interpolation = field.copiedInterpolationValidity();
            scalarTotal(output, interpolation.length, 1);
            for (int start = 0; start < interpolation.length; start += MAX_ENTRIES) {
                int end = Math.min(interpolation.length, start + MAX_ENTRIES);
                count(output, end - start);
                for (int index = start; index < end; index++) {
                    output.writeBoolean(interpolation[index]);
                }
            }
        }
    }

    private static EvidenceSnapshot evidence(DataInputStream input, int version) throws IOException {
        String id = str(input);
        LocalMetricFrame frame = frame(input);
        RasterMetricTransform transform = transform(input);
        EvidenceResolution resolution = resolution(input);
        MetricRegion decision = region(input);
        MetricRegion evidence = region(input);
        RasterResamplingProvenance provenance = provenance(input);
        String source = str(input);
        int fieldCount = count(input);
        Map<String, ScalarEvidenceField> fields = new LinkedHashMap<>();
        for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
            String name = str(input);
            int width = input.readInt();
            int height = input.readInt();
            long product = Math.multiplyExact((long) width, height);
            if (width < 2 || height < 2 || product > Format15Safety.MAX_ARTIFACT_BYTES / 9) {
                throw new IllegalArgumentException("Invalid evidence dimensions");
            }
            EvidenceFieldLineage fieldLineage = lineage(input);
            int length = version == 1 ? count(input) : scalarTotal(input, 9);
            if (length != product) {
                throw new IllegalArgumentException("Evidence value count mismatch");
            }
            admission().bytes(Math.multiplyExact(version == 1 ? 2L : 4L,
                Math.multiplyExact(length, 9L)));
            double[] values = new double[length];
            boolean[] valid = new boolean[length];
            int valueIndex = 0;
            while (valueIndex < length) {
                int chunk = version == 1 ? length : scalarChunk(input, length - valueIndex);
                for (int remaining = chunk; remaining > 0; remaining--, valueIndex++) {
                valid[valueIndex] = input.readBoolean();
                values[valueIndex] = input.readDouble();
                if ((version == 1 || valid[valueIndex])
                        && !Double.isFinite(values[valueIndex])) {
                    throw new IllegalArgumentException(
                            "Replay scalar evidence contains a nonfinite value");
                }
                }
            }
            int cellCount = version == 1 ? count(input) : scalarTotal(input, 1);
            if (cellCount != Math.multiplyExact(width - 1, height - 1)) {
                throw new IllegalArgumentException("Interpolation validity count mismatch");
            }
            admission().bytes(Math.multiplyExact(version == 1 ? 2L : 4L, cellCount));
            boolean[] interpolation = new boolean[cellCount];
            int cellIndex = 0;
            while (cellIndex < cellCount) {
                int chunk = version == 1 ? cellCount : scalarChunk(input, cellCount - cellIndex);
                for (int remaining = chunk; remaining > 0; remaining--, cellIndex++) {
                    interpolation[cellIndex] = input.readBoolean();
                }
            }
            ScalarEvidenceField field = new ScalarEvidenceField(
                    width, height, values, valid, interpolation, fieldLineage);
            if (fields.put(name, field) != null) {
                throw new IllegalArgumentException("Duplicate evidence field");
            }
        }
        return new EvidenceSnapshot(
                id, frame, transform, resolution, decision, evidence, fields, provenance, source);
    }

    private static void network(DataOutputStream out, NetworkSnapshot n) throws IOException {
        str(out, n.snapshotId());
        en(out, n.role());
        str(out, n.datasetIdentity());
        out.writeLong(n.sourceGeneration());
        closure(out, n.closure());
        count(out, n.primitives().size());
        for (PrimitiveKey primitiveKey : orderedKeys(n.primitives().keySet())) {
            key(out, primitiveKey);
            primitive(out, n.primitives().get(primitiveKey));
        }
        count(out, n.incomingReferrerWatches().size());
        for (PrimitiveKey watched : orderedKeys(n.incomingReferrerWatches().keySet())) {
            key(out, watched);
            keys(out, n.incomingReferrerWatches().get(watched));
        }
    }
    private static NetworkSnapshot network(DataInputStream in) throws IOException {
        String id = str(in);
        SnapshotRole role = en(in, SnapshotRole.class);
        String dataset = str(in);
        long generation = in.readLong();
        ClosureDescriptor closure = closure(in);
        int n = count(in);
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            PrimitiveKey key = key(in);
            if (primitives.put(key, primitive(in, key)) != null)
                throw new IllegalArgumentException("Duplicate primitive key");
        }
        int watches = count(in);
        Map<PrimitiveKey, Set<PrimitiveKey>> watch = new LinkedHashMap<>();
        for (int i = 0; i < watches; i++) {
            PrimitiveKey key = key(in);
            if (watch.put(key, keys(in)) != null)
                throw new IllegalArgumentException("Duplicate referrer watch");
        }
        return new NetworkSnapshot(id, role, dataset, generation, closure, primitives, watch);
    }

    private static void options(DataOutputStream out, ModernTracePipeline.Options o)
            throws IOException {
        str(out, o.fieldName());
        GeometryCleanupConfig c = o.cleanup();
        en(out, c.mode());
        en(out, c.preset());
        out.writeDouble(c.rippleScaleMeters());
        out.writeDouble(c.rippleStrength());
        out.writeDouble(c.laplacianStrength());
        out.writeInt(c.laplacianPassCount());
        out.writeDouble(c.simplificationDeviationMeters());
        out.writeDouble(c.minimumFitRetention());
        out.writeBoolean(c.cleanedAlternativeRequested());
        count(out, 0); // Reserved in codec v1; provenance now owns protected occurrences.
        str(out, o.sourceTier());
        out.writeInt(o.mappingPreference());
    }
    private static ModernTracePipeline.Options options(DataInputStream in) throws IOException {
        String field = str(in);
        GeometryCleanupConfig cleanup = new GeometryCleanupConfig(en(in, GeometryCleanupMode.class),
                en(in, GeometryCleanupPreset.class), in.readDouble(), in.readDouble(),
                in.readDouble(), in.readInt(), in.readDouble(), in.readDouble(), in.readBoolean());
        int reservedProtectedIndices = count(in);
        if (reservedProtectedIndices != 0) {
            throw new IllegalArgumentException("Unsupported legacy protected-index options");
        }
        return new ModernTracePipeline.Options(field, cleanup, str(in), in.readInt());
    }

    private static void frame(DataOutputStream o, LocalMetricFrame f) throws IOException {
        str(o, f.projectionId());
        geo(o, f.origin());
        geo(o, f.distortionCertificate().southWest());
        geo(o, f.distortionCertificate().northEast());
    }
    private static LocalMetricFrame frame(DataInputStream i) throws IOException {
        String id = str(i);
        GeographicPoint origin = geo(i), sw = geo(i), ne = geo(i);
        if (!"local-wgs84-tangent-v1".equals(id))
            throw new IllegalArgumentException("Unsupported metric frame");
        return LocalMetricFrame.certifiedEquirectangular(origin, sw, ne);
    }
    private static void transform(DataOutputStream o, RasterMetricTransform t) throws IOException {
        str(o, t.transformId());
        en(o, t.originKind());
        en(o, t.axisUnit());
        point(o, t.origin());
        o.writeDouble(t.xAxisEastMetersPerSourcePixel());
        o.writeDouble(t.xAxisNorthMetersPerSourcePixel());
        o.writeDouble(t.yAxisEastMetersPerSourcePixel());
        o.writeDouble(t.yAxisNorthMetersPerSourcePixel());
        o.writeDouble(t.rasterPixelsPerSourcePixel());
        str(o, t.accuracyCertificate().method());
        o.writeDouble(t.accuracyCertificate().maximumErrorMeters());
        o.writeDouble(t.accuracyCertificate().toleranceMeters());
        o.writeInt(t.accuracyCertificate().verificationPointCount());
    }
    private static RasterMetricTransform transform(DataInputStream i) throws IOException {
        return new RasterMetricTransform(str(i), en(i, RasterMetricTransform.OriginKind.class),
                en(i, RasterMetricTransform.AxisUnit.class), point(i), i.readDouble(),
                i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble(),
                new RasterTransformCertificate(
                        str(i), i.readDouble(), i.readDouble(), i.readInt()));
    }
    private static void resolution(DataOutputStream o, EvidenceResolution r) throws IOException {
        en(o, r.kind());
        optional(o, r.nativePitchMeters());
        o.writeDouble(r.renderedPitchMeters());
        optional(o, r.resampledPitchMeters());
        count(o, r.spatialPitchSamples().size());
        for (EvidenceResolution.PitchSample s : r.spatialPitchSamples()) {
            o.writeDouble(s.chainageMeters());
            optional(o, s.nativePitchMeters());
            o.writeDouble(s.renderedPitchMeters());
        }
    }
    private static EvidenceResolution resolution(DataInputStream i) throws IOException {
        EvidenceResolution.Kind kind = en(i, EvidenceResolution.Kind.class);
        OptionalDouble nativePitch = optional(i);
        double rendered = i.readDouble();
        OptionalDouble resampled = optional(i);
        int n = count(i);
        List<EvidenceResolution.PitchSample> samples = new ArrayList<>();
        for (int x = 0; x < n; x++)
            samples.add(new EvidenceResolution.PitchSample(
                    i.readDouble(), optional(i), i.readDouble()));
        return new EvidenceResolution(kind, nativePitch, rendered, samples, resampled);
    }
    private static void provenance(DataOutputStream o, RasterResamplingProvenance p)
            throws IOException {
        str(o, p.method());
        str(o, p.sourceTransformKind());
        str(o, p.sourceTransformIdentity());
        str(o, p.inputCoordinateConvention());
        str(o, p.scalarOrder());
        str(o, p.validityRule());
        o.writeInt(p.inputWidth());
        o.writeInt(p.inputHeight());
        o.writeInt(p.outputWidth());
        o.writeInt(p.outputHeight());
    }
    private static RasterResamplingProvenance provenance(DataInputStream i) throws IOException {
        return new RasterResamplingProvenance(str(i), str(i), str(i), str(i), str(i), str(i),
                i.readInt(), i.readInt(), i.readInt(), i.readInt());
    }
    private static void lineage(DataOutputStream o, EvidenceFieldLineage l) throws IOException {
        en(o, l.acquisitionKind());
        en(o, l.derivationKind());
        str(o, l.sourcePalette());
        en(o, l.correlationGroup());
        o.writeBoolean(l.completeAggregate());
        strings(o, l.scalarOperations());
    }
    private static EvidenceFieldLineage lineage(DataInputStream i) throws IOException {
        return new EvidenceFieldLineage(en(i, EvidenceFieldLineage.AcquisitionKind.class),
                en(i, EvidenceFieldLineage.DerivationKind.class), str(i),
                en(i, EvidenceCorrelationGroup.class), i.readBoolean(), strings(i));
    }
    private static void chainage(DataOutputStream o, ProfileChainage c) throws IOException {
        count(o, c.cumulativeGroundMeters().size());
        for (double d : c.cumulativeGroundMeters()) o.writeDouble(d);
        o.writeDouble(c.configuredStepMeters());
    }
    private static ProfileChainage chainage(DataInputStream i) throws IOException {
        int n = count(i);
        List<Double> values = new ArrayList<>();
        for (int x = 0; x < n; x++) values.add(i.readDouble());
        return new ProfileChainage(values, i.readDouble());
    }
    private static void closure(DataOutputStream o, ClosureDescriptor c) throws IOException {
        en(o, c.scope());
        str(o, c.queryVersion());
        keys(o, c.primitiveKeys());
        keys(o, c.editableExistingKeys());
        keys(o, c.movableExistingNodeKeys());
        keys(o, c.protectedExistingNodeKeys());
        keys(o, c.removableExistingNodeKeys());
        count(o, c.editableWayOccurrences().size());
        for (PrimitiveKey occurrenceKey : orderedKeys(c.editableWayOccurrences().keySet())) {
            key(o, occurrenceKey);
            List<OccurrenceRange> ranges = c.editableWayOccurrences().get(occurrenceKey);
            count(o, ranges.size());
            for (OccurrenceRange r : ranges) range(o, r);
        }
        count(o, c.externalPorts().size());
        for (ExternalPort p : c.externalPorts()) {
            key(o, p.wayKey());
            key(o, p.boundaryNodeKey());
            key(o, p.outsideNeighborKey());
            o.writeInt(p.boundaryOccurrenceIndex());
            en(o, p.side());
            geo(o, p.outsideNeighborCoordinate());
        }
        region(o, c.collisionEnvelope());
        region(o, c.editRegion());
        o.writeBoolean(c.mayCreateNodes());
        o.writeBoolean(c.wayReferrersComplete());
        o.writeBoolean(c.relationReferrersComplete());
        o.writeBoolean(c.nearbyGeometryComplete());
    }
    private static ClosureDescriptor closure(DataInputStream i) throws IOException {
        ClosureDescriptor.Scope scope = en(i, ClosureDescriptor.Scope.class);
        String query = str(i);
        Set<PrimitiveKey> all = keys(i), editable = keys(i), movable = keys(i),
                          protectedKeys = keys(i), removable = keys(i);
        int n = count(i);
        Map<PrimitiveKey, List<OccurrenceRange>> occurrences = new LinkedHashMap<>();
        for (int x = 0; x < n; x++) {
            PrimitiveKey key = key(i);
            int ranges = count(i);
            List<OccurrenceRange> list = new ArrayList<>();
            for (int r = 0; r < ranges; r++) list.add(range(i));
            if (occurrences.put(key, list) != null)
                throw new IllegalArgumentException("Duplicate occurrence authority");
        }
        n = count(i);
        List<ExternalPort> ports = new ArrayList<>();
        for (int x = 0; x < n; x++)
            ports.add(new ExternalPort(
                    key(i), key(i), key(i), i.readInt(), en(i, ExternalPort.Side.class), geo(i)));
        return new ClosureDescriptor(scope, query, all, editable, movable, protectedKeys, removable,
                occurrences, ports, region(i), region(i), i.readBoolean(), i.readBoolean(),
                i.readBoolean(), i.readBoolean());
    }
    private static void primitive(DataOutputStream o, DetachedPrimitive p) throws IOException {
        if (p instanceof DetachedNode n) {
            o.writeByte(0);
            geo(o, n.coordinate());
            tags(o, n.tags());
            o.writeBoolean(n.deleted());
            o.writeBoolean(n.modified());
        } else if (p instanceof DetachedWay w) {
            o.writeByte(1);
            count(o, w.nodeKeys().size());
            for (PrimitiveKey key : w.nodeKeys()) key(o, key);
            tags(o, w.tags());
            o.writeBoolean(w.deleted());
            o.writeBoolean(w.modified());
        } else if (p instanceof DetachedRelation r) {
            o.writeByte(2);
            count(o, r.members().size());
            for (DetachedRelationMember m : r.members()) {
                key(o, m.memberKey());
                str(o, m.role());
            }
            tags(o, r.tags());
            o.writeBoolean(r.deleted());
            o.writeBoolean(r.modified());
        } else
            throw new IllegalArgumentException("Unknown detached primitive");
    }
    private static DetachedPrimitive primitive(DataInputStream i, PrimitiveKey key)
            throws IOException {
        int type = i.readByte();
        if (type == 0)
            return new DetachedNode(key, geo(i), tags(i), i.readBoolean(), i.readBoolean());
        if (type == 1) {
            int n = count(i);
            List<PrimitiveKey> nodes = new ArrayList<>();
            for (int x = 0; x < n; x++) nodes.add(key(i));
            return new DetachedWay(key, nodes, tags(i), i.readBoolean(), i.readBoolean());
        }
        if (type == 2) {
            int n = count(i);
            List<DetachedRelationMember> members = new ArrayList<>();
            for (int x = 0; x < n; x++) members.add(new DetachedRelationMember(key(i), str(i)));
            return new DetachedRelation(key, members, tags(i), i.readBoolean(), i.readBoolean());
        }
        throw new IllegalArgumentException("Unknown primitive payload");
    }
    private static void region(DataOutputStream o, MetricRegion r) throws IOException {
        count(o, r.polygons().size());
        for (List<MetricPoint> p : r.polygons()) {
            count(o, p.size());
            for (MetricPoint point : p) point(o, point);
        }
    }
    private static MetricRegion region(DataInputStream i) throws IOException {
        int n = count(i);
        List<List<MetricPoint>> polygons = new ArrayList<>();
        for (int x = 0; x < n; x++) {
            int points = count(i);
            List<MetricPoint> p = new ArrayList<>();
            for (int y = 0; y < points; y++) p.add(point(i));
            polygons.add(p);
        }
        return new MetricRegion(polygons);
    }
    private static void tags(DataOutputStream o, Map<String, String> tags) throws IOException {
        count(o, tags.size());
        for (Map.Entry<String, String> e : new TreeMap<>(tags).entrySet()) {
            str(o, e.getKey());
            str(o, e.getValue());
        }
    }
    private static Map<String, String> tags(DataInputStream i) throws IOException {
        int n = count(i);
        Map<String, String> result = new LinkedHashMap<>();
        for (int x = 0; x < n; x++) {
            String key = str(i), value = str(i);
            if (result.put(key, value) != null)
                throw new IllegalArgumentException("Duplicate tag");
        }
        return result;
    }
    private static void keys(DataOutputStream o, Set<PrimitiveKey> values) throws IOException {
        count(o, values.size());
        for (PrimitiveKey k : orderedKeys(values)) key(o, k);
    }
    private static Set<PrimitiveKey> keys(DataInputStream i) throws IOException {
        int n = count(i);
        Set<PrimitiveKey> result = new LinkedHashSet<>();
        for (int x = 0; x < n; x++)
            if (!result.add(key(i)))
                throw new IllegalArgumentException("Duplicate primitive key");
        return result;
    }
    private static List<PrimitiveKey> orderedKeys(Set<PrimitiveKey> values) {
        return values.stream()
                .sorted(Comparator.comparing((PrimitiveKey k) -> k.type().ordinal())
                                .thenComparing(k -> k.identityKind().ordinal())
                                .thenComparingLong(PrimitiveKey::id))
                .toList();
    }
    private static void key(DataOutputStream o, PrimitiveKey k) throws IOException {
        en(o, k.type());
        en(o, k.identityKind());
        o.writeLong(k.id());
    }
    private static PrimitiveKey key(DataInputStream i) throws IOException {
        return new PrimitiveKey(en(i, PrimitiveKey.Type.class),
                en(i, PrimitiveKey.IdentityKind.class), i.readLong());
    }
    private static void range(DataOutputStream o, OccurrenceRange r) throws IOException {
        o.writeInt(r.firstIndex());
        o.writeInt(r.lastIndex());
    }
    private static OccurrenceRange range(DataInputStream i) throws IOException {
        return new OccurrenceRange(i.readInt(), i.readInt());
    }
    private static void geo(DataOutputStream o, GeographicPoint p) throws IOException {
        o.writeDouble(p.latitudeDegrees());
        o.writeDouble(p.longitudeDegrees());
    }
    private static GeographicPoint geo(DataInputStream i) throws IOException {
        return new GeographicPoint(i.readDouble(), i.readDouble());
    }
    private static void point(DataOutputStream o, MetricPoint p) throws IOException {
        o.writeDouble(p.xMeters());
        o.writeDouble(p.yMeters());
    }
    private static MetricPoint point(DataInputStream i) throws IOException {
        return new MetricPoint(i.readDouble(), i.readDouble());
    }
    private static void optional(DataOutputStream o, OptionalDouble v) throws IOException {
        o.writeBoolean(v.isPresent());
        if (v.isPresent())
            o.writeDouble(v.getAsDouble());
    }
    private static OptionalDouble optional(DataInputStream i) throws IOException {
        return i.readBoolean() ? OptionalDouble.of(i.readDouble()) : OptionalDouble.empty();
    }
    private static <E extends Enum<E>> void en(DataOutputStream o, E value) throws IOException {
        str(o, value.name());
    }
    private static <E extends Enum<E>> E en(DataInputStream i, Class<E> type) throws IOException {
        try {
            return Enum.valueOf(type, str(i));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown enum value for " + type.getSimpleName(), e);
        }
    }
    private static void strings(DataOutputStream output, List<String> values) throws IOException {
        count(output, values.size());
        for (String value : values) {
            str(output, value);
        }
    }

    private static List<String> strings(DataInputStream input) throws IOException {
        int size = count(input);
        List<String> values = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            values.add(str(input));
        }
        return values;
    }

    private static void count(DataOutputStream output, int count) throws IOException {
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Replay collection exceeds component budget");
        }
        output.writeInt(count);
    }

    /** Total primitive-array length; the 16 MiB output and 64 MiB peak gates remain independent. */
    private static void scalarTotal(DataOutputStream output, int count, int bytesPerCell)
            throws IOException {
        if (count < 0 || (long) count * bytesPerCell > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Replay scalar field exceeds artifact budget");
        }
        output.writeInt(count);
    }

    private static int scalarTotal(DataInputStream input, int bytesPerCell) throws IOException {
        int count = input.readInt();
        if (count < 0 || (long) count * bytesPerCell > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Replay scalar field exceeds artifact budget");
        }
        return count;
    }

    /** One v2 primitive chunk stays under the unchanged generic 250k component limit. */
    private static int scalarChunk(DataInputStream input, int remaining) throws IOException {
        int count = input.readInt();
        if (count <= 0 || count > MAX_ENTRIES || count > remaining) {
            throw new IllegalArgumentException("Replay scalar chunk is invalid");
        }
        return count;
    }

    private static int count(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Replay collection exceeds component budget");
        }
        admission().entries(count);
        return count;
    }

    private static void str(DataOutputStream output, String value) throws IOException {
        Format15Safety.requireSafeText(value);
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_TEXT) {
            throw new IllegalArgumentException("Replay text exceeds component budget");
        }
        output.writeInt(data.length);
        output.write(data);
    }

    private static String str(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_TEXT || length > input.available()) {
            throw new IllegalArgumentException("Replay text exceeds component budget");
        }
        admission().bytes(40L + 3L * length);
        byte[] data = new byte[length];
        input.readFully(data);
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data))
                            .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Replay text is not strict UTF-8", exception);
        }
        Format15Safety.requireSafeText(value);
        return value;
    }

    private static void safeMetadata(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Null replay text");
        }
        Format15Safety.requireSafeExportedMetadata(value);
    }

    private static Admission admission() {
        Admission value = DECODE_ADMISSION.get();
        if (value == null) {
            throw new IllegalStateException("Replay decode admission is unavailable");
        }
        return value;
    }

    private static void admit(FrozenReplayInput input, Admission admission) {
        admission.bytes(4096);
        EvidenceSnapshot evidence = input.evidence();
        admitMetadata(evidence.snapshotId(), admission);
        admitMetadata(evidence.sourceIdentity(), admission);
        admitMetadata(evidence.coordinateFrame().projectionId(), admission);
        admitMetadata(evidence.transform().transformId(), admission);
        admitMetadata(evidence.transform().accuracyCertificate().method(), admission);
        RasterResamplingProvenance resampling = evidence.resampling();
        admitMetadata(resampling.method(), admission);
        admitMetadata(resampling.sourceTransformKind(), admission);
        admitMetadata(resampling.sourceTransformIdentity(), admission);
        admitMetadata(resampling.inputCoordinateConvention(), admission);
        admitMetadata(resampling.scalarOrder(), admission);
        admitMetadata(resampling.validityRule(), admission);
        admission.entries(evidence.fields().size());
        for (Map.Entry<String, ScalarEvidenceField> entry :
                new TreeMap<>(evidence.fields()).entrySet()) {
            admitMetadata(entry.getKey(), admission);
            ScalarEvidenceField field = entry.getValue();
            admitMetadata(field.lineage().sourcePalette(), admission);
            admission.entries(field.lineage().scalarOperations().size());
            for (String operation : field.lineage().scalarOperations()) {
                admitMetadata(operation, admission);
            }
            long values = Math.multiplyExact((long) field.width(), field.height());
            long cells = Math.multiplyExact((long) field.width() - 1L, (long) field.height() - 1L);
            if (values > Format15Safety.MAX_ARTIFACT_BYTES / 9) {
                throw new IllegalArgumentException("Replay scalar field exceeds artifact budget");
            }
            admission.bytes(Math.multiplyExact(4L,
                    Math.addExact(Math.multiplyExact(values, 9L), cells)));
        }
        TraceRequest request = input.request();
        admitMetadata(request.evidenceSnapshotId(), admission);
        admitMetadata(request.evidenceContentHash(), admission);
        admitMetadata(request.networkSnapshotId(), admission);
        admitMetadata(request.networkContentHash(), admission);
        admitMetadata(request.settingsHash(), admission);
        admitMetadata(request.parameterHash(), admission);
        admitMetadata(request.samplerId(), admission);
        admission.entries(request.profileChainage().cumulativeGroundMeters().size());
        request.corridorInput().ifPresent(
                corridor -> admission.entries(corridor.profileLocations().size()));
        admission.entries(evidence.resolution().spatialPitchSamples().size());
        admission.entries(input.request().evidenceResolution().spatialPitchSamples().size());
        admitRegion(evidence.decisionRegion(), admission);
        admitRegion(evidence.evidenceRegion(), admission);
        admitMetadata(input.options().fieldName(), admission);
        admitMetadata(input.options().sourceTier(), admission);
        NetworkSnapshot network = input.network();
        admitMetadata(network.snapshotId(), admission);
        admitMetadata(network.datasetIdentity(), admission);
        admitMetadata(network.closure().queryVersion(), admission);
        admission.entries(network.primitives().size());
        admission.entries(network.incomingReferrerWatches().size());
        network.primitives().values().forEach(primitive -> {
            primitive.tags().forEach((key, value) -> {
                admitString(key, admission);
                admitString(value, admission);
            });
            if (primitive instanceof DetachedWay way) {
                admission.entries(way.nodeKeys().size());
                admission.entries(way.tags().size());
            } else if (primitive instanceof DetachedRelation relation) {
                admission.entries(relation.members().size());
                relation.members().forEach(member -> admitString(member.role(), admission));
                admission.entries(relation.tags().size());
            } else if (primitive instanceof DetachedNode node) {
                admission.entries(node.tags().size());
            }
        });
        network.incomingReferrerWatches().values().forEach(
                watches -> admission.entries(watches.size()));
        ClosureDescriptor closure = network.closure();
        admission.entries(closure.primitiveKeys().size());
        admission.entries(closure.editableExistingKeys().size());
        admission.entries(closure.movableExistingNodeKeys().size());
        admission.entries(closure.protectedExistingNodeKeys().size());
        admission.entries(closure.removableExistingNodeKeys().size());
        admission.entries(closure.editableWayOccurrences().size());
        closure.editableWayOccurrences().values().forEach(
                ranges -> admission.entries(ranges.size()));
        admission.entries(closure.externalPorts().size());
        admitRegion(closure.collisionEnvelope(), admission);
        admitRegion(closure.editRegion(), admission);
    }

    private static void admitMetadata(String value, Admission admission) {
        safeMetadata(value);
        admitString(value, admission);
    }

    private static void admitString(String value, Admission admission) {
        Format15Safety.requireSafeText(value);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT) {
            throw new IllegalArgumentException("Replay text exceeds component budget");
        }
        admission.bytes(40L + 3L * bytes);
    }

    private static void admitRegion(MetricRegion region, Admission admission) {
        admission.entries(region.polygons().size());
        region.polygons().forEach(polygon -> admission.entries(polygon.size()));
    }

    private static final class Admission {
        private long aggregateEntries;
        private long retainedBytes;

        void entries(long count) {
            if (count < 0 || count > MAX_ENTRIES
                    || aggregateEntries > MAX_AGGREGATE_ENTRIES - count) {
                throw new IllegalArgumentException("Replay aggregate collection budget exceeded");
            }
            aggregateEntries += count;
            bytes(Math.multiplyExact(64L, count));
        }

        void bytes(long count) {
            if (count < 0 || retainedBytes > MAX_RETAINED_BYTES - count) {
                throw new IllegalArgumentException(
                        "Replay aggregate retained-memory budget exceeded");
            }
            retainedBytes += count;
        }
    }

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maximum;

        BoundedByteArrayOutputStream(int maximum) {
            super(Math.min(maximum, 8192));
            this.maximum = maximum;
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            requireCapacity(length);
            super.write(bytes, offset, length);
        }

        private void requireCapacity(int additional) {
            if (additional < 0 || count > maximum - additional) {
                throw new IllegalArgumentException("Frozen replay input exceeds byte budget");
            }
        }
    }
}
