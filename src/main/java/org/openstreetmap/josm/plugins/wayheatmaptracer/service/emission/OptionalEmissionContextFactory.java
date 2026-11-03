package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
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
        private boolean producing;
        private RetainedScalarProfiles produced;
        private final double rasterHalfWidth, rasterStep, rasterSourcePitch;

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
            double outputPitch = source.resolution().outputRasterPitchMeters();
            rasterHalfWidth = halfWidth / outputPitch;
            rasterStep = input.lateralStepMeters() / outputPitch;
            rasterSourcePitch = DetachedScalarProfileSampler.emissionSourcePitchRasterPixels(
                source, input, chainage.sourceOriginGroundMeters(), budget);
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
        /** Claims the exact pre-admitted native producer once, before its first allocation. */
        public synchronized void beginProduction(EvidenceSnapshot evidence, String selectedField,
                List<DetachedProfileSamplingLocation> locations, double width, double step, double pitch,
                EmissionWorkBudget sharedBudget) {
            if (source == null || published || producing || produced != null || owner.isClosed()
                    || source != evidence || !fieldName.equals(selectedField) || budget != sharedBudget
                    || locations != input.profileLocations()
                    || Double.doubleToLongBits(width) != Double.doubleToLongBits(rasterHalfWidth)
                    || Double.doubleToLongBits(step) != Double.doubleToLongBits(rasterStep)
                    || Double.doubleToLongBits(pitch) != Double.doubleToLongBits(rasterSourcePitch)) {
                throw new IllegalArgumentException("Native producer does not match its active reserved permit");
            }
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            producing = true;
        }

        /** Completes only the private producer result belonging to this active permit. */
        public synchronized void completeProduction(RetainedScalarProfiles retained) {
            if (!producing || source == null || owner.isClosed() || retained == null || !retained.boundTo(this))
                throw new IllegalArgumentException("Native producer is not active on this permit");
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 0);
            produced = retained;
            producing = false;
        }

        /** Actual retained-query admission; these methods grant no independent allowance. */
        public void beforeNodeRead() { budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1); }
        public void beforeCellQuery() { budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1); }
        public void preparation(long units) { budget.charge(EmissionWorkBudget.Operation.PREPARATION, units); }
        public boolean routePositionAuthorized(MetricPoint point, CancellationProbe cancellation) {
            return source.decisionRegion().contains(point, cancellation,
                units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units));
        }
        public boolean routeSegmentAuthorized(MetricPoint first, MetricPoint second, CancellationProbe cancellation) {
            return source.decisionRegion().containsSegment(first, second, cancellation,
                units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units), owner);
        }
        @Override public synchronized void close() {
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
            evidence, fieldName, input, chainage, halfWidth, checkpoints, budget);
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

    /**
     * Captures a structural original-row view over the actual retained producer output.
     * This public descriptor path is deliberately non-authoritative for production.
     */
    public static LegacyEmissionBinding createLegacyBinding(SourceBinding binding,
            RetainedScalarProfiles retained, List<Integer> originalRowToRetainedObservation,
            double trackerNormalizationRasterPx,
            List<LegacyEmissionBinding.ProtectedOccurrence> protectedOccurrences) {
        if (binding == null || retained == null || originalRowToRetainedObservation == null
                || originalRowToRetainedObservation.isEmpty() || protectedOccurrences == null
                || !Double.isFinite(trackerNormalizationRasterPx) || trackerNormalizationRasterPx <= 0.0
                || !retained.boundTo(binding) || binding.produced != retained || binding.published
                || binding.owner.isClosed()) {
            throw new IllegalArgumentException("Legacy binding requires the active retained producer");
        }
        List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile> l0
            = retained.profiles().levelZeroProfiles();
        int previous = -1;
        long referenceCount = 0;
        for (int row = 0; row < originalRowToRetainedObservation.size(); row++) {
            int index = originalRowToRetainedObservation.get(row);
            binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (index <= previous || index < 0 || index >= l0.size()) {
                throw new IllegalArgumentException("Original row mapping must be strictly increasing and in range");
            }
            previous = index;
            referenceCount = Math.addExact(referenceCount, l0.get(index).peaks().size());
        }
        long bytes = Math.addExact(256L, Math.addExact(
            AttemptMemoryLedger.referenceArrayBytes(originalRowToRetainedObservation.size() * 2L),
            Math.addExact(Math.multiplyExact((long) originalRowToRetainedObservation.size(), 192L),
                Math.addExact(Math.multiplyExact(referenceCount, 24L),
                    Math.addExact(AttemptMemoryLedger.referenceArrayBytes(protectedOccurrences.size()),
                        Math.multiplyExact((long) protectedOccurrences.size(), 24L))))));
        binding.budget().charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var reservation = binding.owner.reserve(bytes);
        boolean success = false;
        try {
            List<Integer> mapping = List.copyOf(originalRowToRetainedObservation);
            List<LegacyEmissionBinding.OriginalObservation> observations = new ArrayList<>(mapping.size());
            for (int row = 0; row < mapping.size(); row++) {
                binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                int retainedIndex = mapping.get(row);
                var profile = l0.get(retainedIndex);
                List<LegacyEmissionBinding.PeakOwner> peaks = new ArrayList<>(profile.peaks().size());
                for (int peak = 0; peak < profile.peaks().size(); peak++) {
                    binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    peaks.add(new LegacyEmissionBinding.PeakOwner(peak, profile.peaks().get(peak)));
                }
                boolean censored = !profile.anchorWithinRaster()
                    || profile.intensitySamples().stream().anyMatch(sample -> !sample.insideRaster());
                boolean unsupported = profile.peaks().isEmpty() || profile.peaks().stream().allMatch(peak ->
                    peak.syntheticCenter() && peak.supportWidthPx() <= 0.0 && peak.prominence() <= 0.0
                        && peak.maxProfileIntensity() <= 0.0);
                var sourceClass = censored ? NativeConnectorEvaluator.SourceClass.CENSORED
                    : unsupported ? NativeConnectorEvaluator.SourceClass.GAP
                    : NativeConnectorEvaluator.SourceClass.OBSERVED;
                observations.add(new LegacyEmissionBinding.OriginalObservation(
                    row, retainedIndex, profile, sourceClass, peaks));
            }
            var result = new LegacyEmissionBinding(binding, retained.profiles(), mapping, observations,
                trackerNormalizationRasterPx, protectedOccurrences, reservation);
            success = true;
            return result;
        } finally {
            if (!success) reservation.close();
        }
    }

    public static SampledEmissionInput fromDetached(SourceBinding binding, RetainedScalarProfiles retained,
            LegacyEmissionBinding legacyBinding) {
        return publish(binding, retained, OptionalEmissionContext.Origin.DETACHED_FROZEN,
            java.util.Optional.ofNullable(legacyBinding));
    }

    public static SampledEmissionInput fromDeclaredSyntheticMetricGrid(SourceBinding binding,
            RetainedScalarProfiles retained, LegacyEmissionBinding legacyBinding) {
        return publish(binding, retained, OptionalEmissionContext.Origin.DECLARED_SYNTHETIC_METRIC_GRID,
            java.util.Optional.ofNullable(legacyBinding));
    }

    private static SampledEmissionInput publish(SourceBinding binding, RetainedScalarProfiles retained,
            OptionalEmissionContext.Origin origin) {
        return publish(binding, retained, origin, Optional.empty());
    }

    private static SampledEmissionInput publish(SourceBinding binding, RetainedScalarProfiles retained,
            OptionalEmissionContext.Origin origin, Optional<LegacyEmissionBinding> legacyBinding) {
        if (binding == null) throw new IllegalArgumentException("Emission binding is required");
        synchronized (binding) {
        if (binding.published || binding.owner.isClosed() || binding.source == null
                || binding.origin != origin || retained == null
                || !retained.boundTo(binding) || binding.producing || binding.produced != retained
                || !retained.profileLocations().equals(binding.input.profileLocations())
                || retained.profiles().levelZeroProfiles().size() != binding.input.profileLocations().size()
                || retained.levelZero().reduction() != 1) {
            throw new IllegalArgumentException("Retained scalar producer does not match its emission binding");
        }
        binding.budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var context = new OptionalEmissionContext(binding, retained);
        legacyBinding.ifPresent(value -> value.validatePublication(binding, retained, context));
        var result = new SampledEmissionInput(retained.profiles(), context, binding.budget, binding.owner,
            legacyBinding);
        binding.budget.charge(EmissionWorkBudget.Operation.PREPARATION, 0);
        binding.materialization.adopt(retained.profiles());
        binding.metadata.adopt(context);
        legacyBinding.ifPresent(LegacyEmissionBinding::adoptRetainedMetadata);
        binding.published = true;
        binding.source = null;
        return result;
        }
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
