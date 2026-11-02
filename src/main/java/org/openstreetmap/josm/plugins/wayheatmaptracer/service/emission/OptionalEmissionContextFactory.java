package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.Optional;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedScalarProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RetainedScalarProfiles;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Source-specific, pre-admitted construction of factual optional-emission input. */
public final class OptionalEmissionContextFactory {
    private OptionalEmissionContextFactory() { }

    /**
     * Opaque construction permit owning caller-admitted retention before native production.
     * Close on failure; publishing an input transfers this lifetime to that input.
     */
    public static final class SourceBinding implements AutoCloseable {
        private EvidenceSnapshot source;
        private final String fieldName;
        private final CorridorTraceInput input;
        private final ProfileChainage chainage;
        private final double halfWidth;
        private final String contentHash;
        private final String requestIdentity;
        private final Optional<TraceBudgets> originalBudgets;
        private final OptionalEmissionContext.Origin origin;
        private final EmissionWorkBudget budget;
        private final AttemptMemoryLedger.Owner owner;
        private final AttemptMemoryLedger.Reservation metadata;
        private final AttemptMemoryLedger.Reservation materialization;
        private boolean published;

        private SourceBinding(EvidenceSnapshot source, String fieldName, CorridorTraceInput input,
                ProfileChainage chainage, double halfWidth, String hash, String requestIdentity, Optional<TraceBudgets> originalBudgets,
                OptionalEmissionContext.Origin origin, EmissionWorkBudget budget,
                AttemptMemoryLedger.Owner owner, AttemptMemoryLedger.Reservation metadata,
                AttemptMemoryLedger.Reservation materialization) {
            this.source = source;
            this.fieldName = fieldName;
            this.input = input;
            this.chainage = chainage;
            this.halfWidth = halfWidth;
            this.contentHash = hash;
            this.requestIdentity = requestIdentity;
            this.originalBudgets = originalBudgets;
            this.origin = origin;
            this.budget = budget;
            this.owner = owner;
            this.metadata = metadata;
            this.materialization = materialization;
        }

        public long materializationBytes() { return materialization.bytes(); }
        EvidenceSnapshot source() { return source; }
        String fieldName() { return fieldName; }
        ProfileChainage chainage() { return chainage; }
        double halfWidth() { return halfWidth; }
        double lateralStep() { return input.lateralStepMeters(); }
        String contentHash() { return contentHash; }
        String requestIdentity() { return requestIdentity; }
        Optional<TraceBudgets> originalBudgets() { return originalBudgets; }
        OptionalEmissionContext.Origin origin() { return origin; }
        EmissionWorkBudget budget() { return budget; }
        @Override public void close() {
            if (!published) owner.close();
            source = null; // The final context retains facts/L0, not the complete original source snapshot.
        }
    }

    /** Binds the actual frozen request before any retained L0/profile materialization. */
    public static SourceBinding bindDetached(TraceRequest request, EvidenceSnapshot evidence,
            String fieldName, EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        requireCommon(evidence, fieldName, budget, owner);
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 0);
        if (request == null || request.corridorInput().isEmpty()
                || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                || !request.evidenceResolution().equals(evidence.resolution())) {
            throw new IllegalArgumentException("Emission request does not match its frozen source");
        }
        var limits = budget.snapshot();
        if (limits.workLimit() > Math.min(128_000_000L, request.budgets().maximumTransitions())
                || limits.pairLimit() > Math.min(8_000_000L, request.budgets().maximumPairVisits())) {
            throw new IllegalArgumentException("Shared emission work budget exceeds request limits");
        }
        return bind(evidence, fieldName, request.corridorInput().orElseThrow(), request.profileChainage(),
            request.permissions().ordinaryRadiusMeters(), request.evidenceContentHash(),
            request, request.budgets(), OptionalEmissionContext.Origin.DETACHED_FROZEN, budget, owner);
    }

    /** Requires explicit synthetic lineage and an exact constructed metric grid; never certifies production. */
    public static SourceBinding bindDeclaredSyntheticMetricGrid(EvidenceSnapshot evidence,
            String fieldName, CorridorTraceInput input, ProfileChainage chainage, double halfWidth,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        requireCommon(evidence, fieldName, budget, owner);
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 0);
        if (evidence.fields().get(fieldName).lineage().acquisitionKind()
                    != EvidenceFieldLineage.AcquisitionKind.SYNTHETIC
                || evidence.transform().originKind() != RasterMetricTransform.OriginKind.METRIC_FIRST_PIXEL_CENTER
                || !evidence.transform().accuracyCertificate().method().equals("exact-constructed-metric-grid-v1")) {
            throw new IllegalArgumentException("Synthetic emission input requires explicitly declared metric truth");
        }
        return bind(evidence, fieldName, input, chainage, halfWidth, null, null,
            null, OptionalEmissionContext.Origin.DECLARED_SYNTHETIC_METRIC_GRID, budget, owner);
    }

    private static void requireCommon(EvidenceSnapshot evidence, String fieldName, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        if (evidence == null || fieldName == null || fieldName.isBlank() || budget == null || owner == null
                || !evidence.fields().containsKey(fieldName)) {
            throw new IllegalArgumentException("Emission source, named field and shared execution owners are required");
        }
    }

    private static SourceBinding bind(EvidenceSnapshot evidence, String fieldName, CorridorTraceInput input,
            ProfileChainage chainage, double halfWidth, String expectedHash, TraceRequest request,
            TraceBudgets originalBudgets, OptionalEmissionContext.Origin origin,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner parent) {
        if (input == null || chainage == null || input.profileLocations().size()
                != chainage.cumulativeGroundMeters().size() || !Double.isFinite(halfWidth) || halfWidth <= 0) {
            throw new IllegalArgumentException("Emission locations do not match source ground chainage");
        }
        for (int index = 0; index < input.profileLocations().size(); index++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var location = input.profileLocations().get(index);
            if (!location.coordinateFrame().equals(evidence.coordinateFrame())
                    || !location.rasterTransform().equals(evidence.transform())
                    || Double.doubleToLongBits(location.cumulativeGroundDistanceMeters())
                        != Double.doubleToLongBits(chainage.cumulativeGroundMeters().get(index))) {
                throw new IllegalArgumentException("Emission location frame, phase or ground chainage differs");
            }
        }
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, input.profileLocations().size());
        CancellationProbe checkpoints = () -> {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            return false;
        };
        long materializationBytes = DetachedScalarProfileSampler.estimatedEmissionMaterializationBytes(
            evidence, fieldName, input, chainage, halfWidth, checkpoints);
        long metadataBytes = estimatedMetadataBytes(evidence, fieldName, input, request);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var owner = parent.child("optional-emission-input");
        boolean success = false;
        try {
            var metadata = owner.reserve(metadataBytes);
            var materialization = owner.reserve(materializationBytes);
            String actualHash;
            // EvidenceSnapshot.canonicalHash copies source arrays. Admit those temporaries BEFORE hashing.
            try (var hashMemory = owner.reserve(estimatedHashBytes(evidence))) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, hashWorkUnits(evidence));
                actualHash = evidence.canonicalHash();
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 0);
            }
            if (expectedHash != null && !expectedHash.equals(actualHash)) {
                throw new IllegalArgumentException("Emission source content differs from the frozen request");
            }
            // Construct the retained identity string only after its metadata reservation is admitted.
            String requestIdentity = request == null ? "declared-synthetic-metric-grid"
                : request.selectedWayKey() + ":" + request.selectedRange() + ":" + request.engine()
                    + ":" + request.geometryMode() + ":" + request.networkSnapshotId() + ":"
                    + request.networkContentHash() + ":" + request.settingsHash() + ":" + request.parameterHash()
                    + ":" + request.samplerId() + ":" + request.permissions().canonicalIdentity();
            var result = new SourceBinding(evidence, fieldName, input, chainage, halfWidth, actualHash, requestIdentity,
                Optional.ofNullable(originalBudgets), origin, budget, owner, metadata, materialization);
            success = true;
            return result;
        } finally {
            if (!success) owner.close();
        }
    }

    /** Publishes only the L0/profile producer owned by this exact detached binding. */
    public static SampledEmissionInput fromDetached(SourceBinding binding, RetainedScalarProfiles retained) {
        return publish(binding, retained, OptionalEmissionContext.Origin.DETACHED_FROZEN);
    }

    /** Publishes declared test truth under its permanently nonproduction origin. */
    public static SampledEmissionInput fromDeclaredSyntheticMetricGrid(SourceBinding binding,
            RetainedScalarProfiles retained) {
        return publish(binding, retained, OptionalEmissionContext.Origin.DECLARED_SYNTHETIC_METRIC_GRID);
    }

    private static SampledEmissionInput publish(SourceBinding binding, RetainedScalarProfiles retained,
            OptionalEmissionContext.Origin origin) {
        if (binding == null || binding.published || binding.owner.isClosed() || binding.source == null
                || binding.origin != origin || retained == null
                || !retained.boundTo(binding.source, binding.fieldName, binding.budget)
                || !retained.profileLocations().equals(binding.input.profileLocations())
                || retained.profiles().levelZeroProfiles().size() != binding.input.profileLocations().size()
                || retained.levelZero().reduction() != 1) {
            throw new IllegalArgumentException("Retained scalar producer does not match its emission binding");
        }
        binding.budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var context = new OptionalEmissionContext(binding, retained);
        var result = new SampledEmissionInput(retained.profiles(), context, binding.budget, binding.owner);
        binding.budget.charge(EmissionWorkBudget.Operation.PREPARATION, 0);
        binding.materialization.adopt(retained.profiles());
        binding.metadata.adopt(context);
        binding.published = true;
        binding.source = null;
        return result;
    }

    private static long estimatedHashBytes(EvidenceSnapshot evidence) {
        long result = 4096;
        for (var field : evidence.fields().values()) {
            long nodes = Math.multiplyExact((long)field.width(), field.height());
            long cells = Math.multiplyExact((long)field.width() - 1, (long)field.height() - 1);
            result = Math.addExact(result, Math.addExact(Math.multiplyExact(nodes, 9), cells + 128));
        }
        return result;
    }

    private static long hashWorkUnits(EvidenceSnapshot evidence) {
        long result = 1;
        for (var field : evidence.fields().values()) {
            result = Math.addExact(result, Math.addExact(Math.multiplyExact((long)field.width(), field.height()),
                Math.multiplyExact((long)field.width() - 1, (long)field.height() - 1)));
        }
        return result;
    }

    private static long estimatedMetadataBytes(EvidenceSnapshot evidence, String fieldName,
            CorridorTraceInput input, TraceRequest request) {
        long bytes = 8192;
        bytes = Math.addExact(bytes, Math.multiplyExact((long)input.profileLocations().size(), 768));
        bytes = Math.addExact(bytes, Math.multiplyExact((long)evidence.resolution().spatialPitchSamples().size(), 128));
        bytes = Math.addExact(bytes, regionBytes(evidence.decisionRegion()));
        bytes = Math.addExact(bytes, regionBytes(evidence.evidenceRegion()));
        for (String text : java.util.List.of(evidence.sourceIdentity(), evidence.snapshotId(), fieldName,
                evidence.coordinateFrame().projectionId(),
                evidence.coordinateFrame().distortionCertificate().method(), evidence.transform().transformId(),
                evidence.transform().accuracyCertificate().method())) {
            bytes = Math.addExact(bytes, Math.addExact(64, Math.multiplyExact((long)text.length(), 4)));
        }
        if (request != null) {
            // Fixed overhead covers primitive keys/range, mode names, radii and permission hash.
            bytes = Math.addExact(bytes, 4096);
            for (String text : java.util.List.of(request.networkSnapshotId(), request.networkContentHash(),
                    request.settingsHash(), request.parameterHash(), request.samplerId())) {
                bytes = Math.addExact(bytes, Math.addExact(64, Math.multiplyExact((long)text.length(), 4)));
            }
        }
        var lineage = evidence.fields().get(fieldName).lineage();
        bytes = Math.addExact(bytes, 64 + Math.multiplyExact((long)lineage.sourcePalette().length(), 4));
        for (String text : lineage.scalarOperations()) {
            bytes = Math.addExact(bytes, 64 + Math.multiplyExact((long)text.length(), 4));
        }
        return bytes;
    }

    private static long regionBytes(MetricRegion region) {
        long bytes = 64;
        for (var polygon : region.polygons()) {
            bytes = Math.addExact(bytes, Math.addExact(64, Math.multiplyExact((long)polygon.size(), 64)));
        }
        return bytes;
    }
}
