package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
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
    private static final double R4_SYNTHETIC_ROUNDOFF_BOUND = 1.0e-12;

    /** Opaque, single-use permit for the actual prepared managed source and its live selection. */
    public static final class ManagedCaptureBinding implements AutoCloseable {
        private final org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture capture;
        private final java.util.function.Supplier<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig> currentSettings;
        private final EmissionWorkBudget budget;
        private final AttemptMemoryLedger.Owner owner;
        private final AttemptMemoryLedger.Reservation metadata;
        private final org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode alignmentMode;
        private final boolean managedA;
        private org.openstreetmap.josm.plugins.wayheatmaptracer.service.RetainedManagedScalarProfiles producedA;
        private boolean producing, published;
        private RuntimeException terminalFailure;

        private ManagedCaptureBinding(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture capture,
                java.util.function.Supplier<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig> currentSettings,
                EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner,
                AttemptMemoryLedger.Reservation metadata,boolean managedA) {
            this.capture = capture; this.currentSettings = currentSettings;
            this.budget = budget; this.owner = owner; this.metadata = metadata;
            this.alignmentMode = currentSettings.get().alignmentMode();
            this.managedA=managedA;
        }
        public boolean isManagedA(){return managedA;}
        public org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture capture() { return capture; }
        public String detectorMode() { return capture.originalRequest().selectedColor(); }
        public EmissionWorkBudget workBudget() { return budget; }
        public AttemptMemoryLedger.Owner retainedOwner() { return owner; }
        public synchronized void beginProduction() {
            if(managedA){
                // A failed unpublished entry owns its permit cleanup; a consumed permit cannot revoke a result.
                if(terminalFailure!=null)throw terminalFailure;
                if(producing||published)throw new IllegalStateException("Managed source permit is single use");
                try{requireCurrent();producing=true;return;}
                catch(RuntimeException failure){failProduction(failure);throw failure;}
            }
            requireCurrent();
            if (producing || published) throw new IllegalStateException("Managed source permit is single use");
            producing = true;
        }
        synchronized void requireCurrent() {
            if (terminalFailure != null) throw terminalFailure;
            if (owner.isClosed()) throw new IllegalStateException("Managed source permit is closed");
            try {
            var settings = currentSettings.get();
            capture.requireSourceCurrent(settings, budget);
            if (settings.trackerMode() != (managedA?org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.CORRIDOR_AWARE:org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.LEGACY_V02)
                    || settings.alignmentMode() != alignmentMode || settings.adjustJunctionNodes()) {
                throw new IllegalStateException("Managed captured alignment permissions changed");
            }
            capture.requireSelectionCurrent(budget, owner);
            var witness = capture.selectionWitness().orElseThrow();
            if (org.openstreetmap.josm.gui.MainApplication.getLayerManager().getEditDataSet() != witness.dataSet()) {
                throw new IllegalStateException("Managed selected dataset is not the active edit layer");
            }
            } catch (AttemptMemoryLedger.ResourceLimitException | EmissionWorkBudget.LimitExceededException
                    | CancellationException failure) {
                terminalFailure = failure;
                budget.failTerminally(failure);
                throw failure;
            }
        }
        /** Opaque same-attempt final check; all scratch and scan work precedes candidate copy. */
        synchronized FinalPublicationCheck prepareFinalPublication(AttemptMemoryLedger.Owner parent){
            if(terminalFailure!=null)throw terminalFailure;
            if(owner.isClosed())throw new IllegalStateException("Managed source permit is closed");
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,16);
            return new FinalPublicationCheck(capture.prepareFinalPublicationCheck(budget,parent));
        }
        final class FinalPublicationCheck implements AutoCloseable{
            private final org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture.FinalPublicationCheck selection;
            private boolean used;
            private FinalPublicationCheck(org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture.FinalPublicationCheck selection){this.selection=selection;}
            void validate(){
                if(used)throw new IllegalStateException("Managed final source check is consumed");used=true;
                if(terminalFailure!=null)throw terminalFailure;
                if(owner.isClosed())throw new IllegalStateException("Managed source permit is closed");
                // The supplier is resolved first. Every source/selection/profile read follows it;
                // no caller callback occurs after this complete final scan.
                var settings=currentSettings.get();
                selection.validate(settings);
                if(settings.trackerMode()!=(managedA?org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.CORRIDOR_AWARE:org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.LEGACY_V02)
                        ||settings.alignmentMode()!=alignmentMode||settings.adjustJunctionNodes())throw new IllegalStateException("Managed captured alignment permissions changed");
                if(org.openstreetmap.josm.gui.MainApplication.getLayerManager().getEditDataSet()!=capture.selectionWitness().orElseThrow().dataSet())
                    throw new IllegalStateException("Managed selected dataset is not the active edit layer");
            }
            @Override public void close(){selection.close();}
        }
        public synchronized void failProduction(RuntimeException failure) {
            if (terminalFailure == null) terminalFailure = failure;
            budget.failTerminally(failure);
            owner.close();
        }
        @Override public synchronized void close() { if (!published) owner.close(); }
    }

    /** Admits source-owned metadata before the one native grid/profile producer. No acquisition is added. */
    public static ManagedCaptureBinding bindManagedCapture(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture capture,
            java.util.function.Supplier<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig> currentSettings,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner parent) {
        return bindManagedCapture(capture,currentSettings,budget,parent,false);
    }
    /** Explicit native multilevel A permit; normal captured fixed endpoints only. */
    public static ManagedCaptureBinding bindManagedACapture(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture capture,
            java.util.function.Supplier<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig> currentSettings,
            EmissionWorkBudget budget,AttemptMemoryLedger.Owner parent){
        return bindManagedCapture(capture,currentSettings,budget,parent,true);
    }
    private static ManagedCaptureBinding bindManagedCapture(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.PreparedManagedCapture capture,
            java.util.function.Supplier<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig> currentSettings,
            EmissionWorkBudget budget,AttemptMemoryLedger.Owner parent,boolean managedA){
        java.util.Objects.requireNonNull(capture, "prepared capture");
        java.util.Objects.requireNonNull(currentSettings, "current managed settings");
        java.util.Objects.requireNonNull(budget, "shared budget");
        java.util.Objects.requireNonNull(parent, "attempt owner");
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        var settings = currentSettings.get();
        if (settings == null || settings.trackerMode()
                != (managedA?org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.CORRIDOR_AWARE:org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.LEGACY_V02)
                || settings.intensitySamplingMode()
                    != org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode.COLOR_MAPPING
                || settings.adjustJunctionNodes() || capture.selectionWitness().isEmpty()) {
            throw new IllegalArgumentException(managedA?"Managed A emission requires native color-mapped source and captured fixed endpoints":"Managed emission requires native Legacy source and fixed selected occurrences");
        }
        if(managedA){
            var facts=capture.selectionWitness().orElseThrow().frozenSelectedFacts();
            if(facts.size()<2||!facts.get(0).explicitlyFixed()||!facts.get(facts.size()-1).explicitlyFixed())
                throw new IllegalArgumentException("Managed A requires captured fixed endpoints");
            for(int i=1;i+1<facts.size();i++){
                budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
                if(facts.get(i).protectedOccurrence()||facts.get(i).explicitlyFixed())
                    throw new IllegalArgumentException("Managed A protected interiors require a separate safe-interval adapter");
            }
        }
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var owner = parent.child("managed-emission-input");
        try {
            var reservation = owner.reserve(Math.addExact(16384L, Math.addExact(Math.multiplyExact(
                    (long) capture.originalRequest().originalSource().size(), 2048L),
                    Math.multiplyExact((long) (managedA?capture.nativeAProfileCount():capture.originalProfileCount()), 1024L))));
            var binding = new ManagedCaptureBinding(capture, currentSettings, budget, owner, reservation,managedA);
            binding.requireCurrent();
            return binding;
        } catch (RuntimeException failure) {
            if (failure instanceof AttemptMemoryLedger.ResourceLimitException
                    || failure instanceof EmissionWorkBudget.LimitExceededException
                    || failure instanceof CancellationException) budget.failTerminally(failure);
            owner.close(); throw failure;
        }
    }

    /** Publishes only profiles produced on this opaque actual-source permit. */
    public static SampledEmissionInput fromManagedAProduction(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.ManagedAProducerReceipt receipt){
        java.util.Objects.requireNonNull(receipt,"actual A producer receipt");var binding=receipt.binding();
        synchronized(binding){
            binding.requireCurrent();
            if(!binding.managedA||!binding.producing||binding.published||binding.producedA!=null)
                throw new IllegalArgumentException("Managed A producer receipt is foreign or consumed");
            binding.producedA=receipt.retained();
            return fromManagedCapture(binding,receipt.retained());
        }
    }

    /** Publishes only profiles produced on this opaque actual-source permit. */
    public static SampledEmissionInput fromManagedCapture(ManagedCaptureBinding binding,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RetainedManagedScalarProfiles retained) {
        java.util.Objects.requireNonNull(binding, "managed binding");
        synchronized (binding) {
            binding.requireCurrent();
            if (!binding.producing || binding.published || retained == null
                    ||retained.isManagedA()!=binding.managedA
                    ||binding.managedA&&binding.producedA!=retained
                    || retained.sourceCapture().orElse(null) != binding.capture
                    || !retained.boundTo(binding.owner, binding.budget)
                    || retained.sourceParameters().isEmpty() || retained.groundTransform().isEmpty()) {
                throw new IllegalArgumentException("Managed retained producer differs from its actual-source permit");
            }
            var mosaic = retained.sourceMosaic();
            int tileCount = Math.multiplyExact(mosaic.image().getWidth() / 512,
                    mosaic.image().getHeight() / 512);
            if (tileCount == 0 || mosaic.tiles().size() != tileCount
                    || mosaic.tileImages().size() != tileCount
                    || mosaic.tiles().stream().anyMatch(tile -> !tile.usable())) {
                throw new IllegalArgumentException("Managed production requires every acquired usable tile receipt");
            }
            var context = new OptionalEmissionContext(binding, retained);
            var legacy = binding.managedA?null:LegacyEmissionBinding.fromManagedCapture(binding, retained, context);
            var result = new SampledEmissionInput(retained.profileSet(), context, binding.budget,
                    binding.owner, Optional.ofNullable(legacy));
            if(binding.managedA)context.managedAEndpoints().orElseThrow().attachInput(result);
            binding.metadata.adopt(context);
            if(binding.managedA)try(var publication=result.prepareManagedAFinalPublication(binding.owner,256,4096)){
                publication.validate();
            }
            binding.published = true;
            binding.producing = false;
            return result;
        }
    }

    /** Declared facts needed to validate the synthetic R4 full-raster to crop mapping. */
    public record DeclaredSyntheticRasterTruth(
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField fullSourceGrid,
        int cropOriginX,
        int cropOriginY,
        double nativePitchRasterPixels,
        double chainageOriginMeters,
        List<LegacyEmissionBinding.ProtectedOccurrence> protectedOccurrences
    ) {
        public DeclaredSyntheticRasterTruth {
            if (fullSourceGrid == null || cropOriginX < 0 || cropOriginY < 0
                    || !Double.isFinite(nativePitchRasterPixels) || nativePitchRasterPixels <= 0
                    || !Double.isFinite(chainageOriginMeters) || chainageOriginMeters < 0
                    || protectedOccurrences == null) {
                throw new IllegalArgumentException("Declared synthetic raster truth is incomplete");
            }
            protectedOccurrences = List.copyOf(protectedOccurrences);
        }
    }

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
        private RuntimeException terminalFailure;
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
        synchronized void requireNoTerminalFailure() {
            if (terminalFailure != null) throw terminalFailure;
        }
        synchronized void failTerminally(RuntimeException failure) {
            if (terminalFailure == null) terminalFailure = failure;
            owner.close();
            source = null;
            producing = false;
        }
        /** Claims the exact pre-admitted native producer once, before its first allocation. */
        public synchronized void beginProduction(EvidenceSnapshot evidence, String selectedField,
                List<DetachedProfileSamplingLocation> locations, double width, double step, double pitch,
                EmissionWorkBudget sharedBudget) {
            requireNoTerminalFailure();
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
        if (binding != null) binding.requireNoTerminalFailure();
        if (binding == null || retained == null || originalRowToRetainedObservation == null
                || originalRowToRetainedObservation.isEmpty() || protectedOccurrences == null
                || !Double.isFinite(trackerNormalizationRasterPx) || trackerNormalizationRasterPx <= 0.0
                || !retained.boundTo(binding) || binding.produced != retained || binding.published
                || binding.owner.isClosed()) {
            throw new IllegalArgumentException("Legacy binding requires the active retained producer");
        }
        try {
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
        } catch (AttemptMemoryLedger.ResourceLimitException
                | EmissionWorkBudget.LimitExceededException | CancellationException failure) {
            binding.failTerminally(failure);
            throw failure;
        }
    }

    /**
     * Binds the synthetic R4 Legacy profile objects separately from equivalent cropped image measurements.
     * This route is permanently nonproduction and requires a complete declared raster/crop truth record.
     */
    public static LegacyEmissionBinding createDeclaredSyntheticR4Binding(SourceBinding binding,
            RetainedScalarProfiles retained,
            List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile>
                originalProfiles,
            List<Integer> originalRowToRetainedObservation,
            double trackerNormalizationRasterPx,
            DeclaredSyntheticRasterTruth truth) {
        if (binding != null) binding.requireNoTerminalFailure();
        if (binding == null || retained == null || originalProfiles == null || originalProfiles.size() != 17
                || originalRowToRetainedObservation == null || originalRowToRetainedObservation.size() != 17
                || truth == null || truth.protectedOccurrences() == null
                || binding.origin() != OptionalEmissionContext.Origin.DECLARED_SYNTHETIC_METRIC_GRID
                || !retained.boundTo(binding) || binding.produced != retained || binding.published
                || binding.owner.isClosed() || Double.doubleToLongBits(trackerNormalizationRasterPx)
                    != Double.doubleToLongBits(1.0)
                || Double.doubleToLongBits(truth.nativePitchRasterPixels()) != Double.doubleToLongBits(1.0)
                || Double.doubleToLongBits(binding.source().resolution().outputRasterPitchMeters())
                    != Double.doubleToLongBits(1.0)
                || Double.doubleToLongBits(truth.chainageOriginMeters()) != Double.doubleToLongBits(0.0)
                || Double.doubleToLongBits(truth.chainageOriginMeters())
                    != Double.doubleToLongBits(binding.chainage().sourceOriginGroundMeters())) {
            throw new IllegalArgumentException("R4 binding requires its exact declared synthetic producer and units");
        }
        try {
            long peakReferences = 0;
            long nativeSamples = 0;
            for (var profile : originalProfiles) {
                peakReferences = Math.addExact(peakReferences, profile.peaks().size());
                nativeSamples = Math.addExact(nativeSamples, profile.intensitySamples().size());
            }
            long metadataBytes = Math.addExact(512L,
                Math.addExact(AttemptMemoryLedger.referenceArrayBytes(17L * 3L
                        + truth.protectedOccurrences().size() + peakReferences + nativeSamples),
                    Math.addExact(Math.multiplyExact(17L, 256L),
                        Math.addExact(Math.multiplyExact(peakReferences, 224L),
                            Math.multiplyExact(nativeSamples, 64L)))));
            binding.budget().charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
            var reservation = binding.owner.reserve(metadataBytes);
            boolean success = false;
            try {
                var croppedSource = binding.source().fields().get(binding.fieldName());
                var fullSource = truth.fullSourceGrid();
                if (fullSource.width() != 128 || fullSource.height() != 128
                        || truth.cropOriginX() != 18 || truth.cropOriginY() != 17
                        || croppedSource.width() != 93 || croppedSource.height() != 86
                        || croppedSource.lineage().acquisitionKind()
                            != org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage.AcquisitionKind.SYNTHETIC
                        || fullSource.lineage().acquisitionKind()
                            != org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage.AcquisitionKind.SYNTHETIC
                        || croppedSource.lineage().derivationKind() != fullSource.lineage().derivationKind()
                        || croppedSource.lineage().correlationGroup() != fullSource.lineage().correlationGroup()
                        || croppedSource.lineage().completeAggregate() != fullSource.lineage().completeAggregate()
                        || !croppedSource.lineage().scalarOperations().equals(fullSource.lineage().scalarOperations())
                        || retained.profiles().levelZeroProfiles().size() != 19) {
                    throw new IllegalArgumentException("R4 crop dimensions do not match the declared 17-to-19 fixture");
                }
                binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION,
                    Math.multiplyExact((long) croppedSource.width(), croppedSource.height()));
                for (int y = 0; y < croppedSource.height(); y++) for (int x = 0; x < croppedSource.width(); x++) {
                    var expected = fullSource.sample(truth.cropOriginX() + x, truth.cropOriginY() + y);
                    var actual = croppedSource.sample(x, y);
                    if (expected.isPresent() != actual.isPresent()
                            || (expected.isPresent() && Double.doubleToLongBits(expected.getAsDouble())
                                != Double.doubleToLongBits(actual.getAsDouble()))) {
                        throw new IllegalArgumentException("R4 cropped scalar source differs from declared full raster");
                    }
                }

                List<Integer> mapping = List.copyOf(originalRowToRetainedObservation);
                List<LegacyEmissionBinding.OriginalObservation> observations = new ArrayList<>(17);
                var retainedProfiles = retained.profiles().levelZeroProfiles();
                for (int row = 0; row < 17; row++) {
                    binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    int retainedIndex = mapping.get(row);
                    if (retainedIndex != row + 1) {
                        throw new IllegalArgumentException("R4 original rows must map to retained observations 1 through 17");
                    }
                    var nativeProfile = originalProfiles.get(row);
                    var measurementProfile = retainedProfiles.get(retainedIndex);
                    var measurementLocation = retained.profileLocations().get(retainedIndex);
                    boolean xMatches = sameBits(nativeProfile.anchorScreen().x - truth.cropOriginX(),
                        measurementProfile.anchorScreen().x);
                    boolean yMatches = sameBits(nativeProfile.anchorScreen().y - truth.cropOriginY(),
                        measurementProfile.anchorScreen().y);
                    boolean normalXMatches = sameBits(nativeProfile.normalScreen().x, measurementProfile.normalScreen().x);
                    boolean normalYMatches = sameBits(nativeProfile.normalScreen().y, measurementProfile.normalScreen().y);
                    boolean profileChainageMatches = sameBits(nativeProfile.cumulativeGroundDistanceMeters(),
                        measurementProfile.cumulativeGroundDistanceMeters());
                    boolean locationChainageMatches = sameBits(truth.chainageOriginMeters()
                        + nativeProfile.cumulativeGroundDistanceMeters(), measurementLocation.cumulativeGroundDistanceMeters());
                    boolean samplesMatch = nativeProfile.anchorWithinRaster() == measurementProfile.anchorWithinRaster()
                        && nativeProfile.peaks().size() == measurementProfile.peaks().size()
                        && isCensored(nativeProfile) == isCensored(measurementProfile)
                        && isUnsupported(nativeProfile) == isUnsupported(measurementProfile)
                        && sameCompleteProfileEvidence(nativeProfile, measurementProfile);
                    if (!xMatches || !yMatches || !normalXMatches || !normalYMatches || !profileChainageMatches
                            || !locationChainageMatches || !samplesMatch) {
                        int differingSample = -1;
                        int commonSamples = Math.min(nativeProfile.intensitySamples().size(),
                            measurementProfile.intensitySamples().size());
                        for (int sample = 0; sample < commonSamples; sample++) {
                            if (!nativeProfile.intensitySamples().get(sample)
                                    .equals(measurementProfile.intensitySamples().get(sample))) {
                                differingSample = sample;
                                break;
                            }
                        }
                        throw new IllegalArgumentException("R4 row " + row + " parity mismatch x=" + xMatches + " y="
                            + yMatches + " normal=(" + normalXMatches + "," + normalYMatches + ") chainage=("
                            + profileChainageMatches + "," + locationChainageMatches + ") samples=" + samplesMatch
                            + " nativeAnchor=" + nativeProfile.anchorScreen() + " translatedNative=("
                            + (nativeProfile.anchorScreen().x - truth.cropOriginX()) + ","
                            + (nativeProfile.anchorScreen().y - truth.cropOriginY()) + ") measurementAnchor="
                            + measurementProfile.anchorScreen() + " nativeNormal=" + nativeProfile.normalScreen()
                            + " measurementNormal=" + measurementProfile.normalScreen() + " nativeSample0="
                            + nativeProfile.intensitySamples().get(0) + " measurementSample0="
                            + measurementProfile.intensitySamples().get(0) + " sampleCounts="
                            + nativeProfile.intensitySamples().size() + "/" + measurementProfile.intensitySamples().size()
                            + " firstDifferentSample=" + differingSample + " values=" + (differingSample < 0 ? "same-length"
                                : nativeProfile.intensitySamples().get(differingSample) + " vs "
                                    + measurementProfile.intensitySamples().get(differingSample)));
                    }
                    List<LegacyEmissionBinding.PeakOwner> peaks = new ArrayList<>(nativeProfile.peaks().size());
                    java.util.Set<org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak>
                        assignedMeasurementOwners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    for (int peakId = 0; peakId < nativeProfile.peaks().size(); peakId++) {
                        var nativePeak = nativeProfile.peaks().get(peakId);
                        int matches = 0;
                        org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak
                            measurementOwner = null;
                        for (var candidate : measurementProfile.peaks()) {
                            binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                            if (samePeakBand(nativePeak, candidate)) {
                                matches++;
                                measurementOwner = candidate;
                            }
                        }
                        if (matches != 1 || !assignedMeasurementOwners.add(measurementOwner)) {
                            throw new IllegalArgumentException("Each native R4 peak requires one unique same-band measurement owner");
                        }
                        peaks.add(new LegacyEmissionBinding.PeakOwner(peakId, nativePeak, measurementOwner));
                    }
                    boolean censored = isCensored(nativeProfile);
                    boolean unsupported = isUnsupported(nativeProfile);
                    var sourceClass = censored ? NativeConnectorEvaluator.SourceClass.CENSORED
                        : unsupported ? NativeConnectorEvaluator.SourceClass.GAP
                        : NativeConnectorEvaluator.SourceClass.OBSERVED;
                    observations.add(new LegacyEmissionBinding.OriginalObservation(row, retainedIndex, nativeProfile,
                        measurementProfile, sourceClass, peaks));
                }
                    var receipt = new LegacyEmissionBinding.DeclaredSyntheticRasterReceipt(
                        fullSource.width(), fullSource.height(), truth.cropOriginX(), truth.cropOriginY(),
                        truth.nativePitchRasterPixels(), truth.chainageOriginMeters(),
                        System.identityHashCode(fullSource), truth.protectedOccurrences());
                    var result = new LegacyEmissionBinding(binding, retained.profiles(), mapping, observations,
                        trackerNormalizationRasterPx, truth.protectedOccurrences(), reservation, receipt);
                    success = true;
                    return result;
            } finally {
                if (!success) reservation.close();
            }
        } catch (AttemptMemoryLedger.ResourceLimitException
                | EmissionWorkBudget.LimitExceededException | CancellationException failure) {
            binding.failTerminally(failure);
            throw failure;
        }
    }

    private static boolean sameBits(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private static boolean sameCompleteProfileEvidence(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile first,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile second) {
        var a = first.intensitySamples();
        var b = second.intensitySamples();
        if (a.size() != b.size()) return false;
        for (int index = 0; index < a.size(); index++) {
            var left = a.get(index);
            var right = b.get(index);
            if (!sameBits(left.offsetPx(), right.offsetPx()) || left.insideRaster() != right.insideRaster()
                    || !sameSyntheticRoundoff(left.nativeIntensity(), right.nativeIntensity())
                    || !sameSyntheticRoundoff(left.lightFilteredIntensity(), right.lightFilteredIntensity())
                    || !sameSyntheticRoundoff(left.standardFilteredIntensity(), right.standardFilteredIntensity())) {
                return false;
            }
        }
        return true;
    }

    /** Allows only reviewed R4 arithmetic roundoff after identical nodes/geometry pass exact checks. */
    private static boolean sameSyntheticRoundoff(double first, double second) {
        return Double.isFinite(first) && Double.isFinite(second)
            && Math.abs(first - second) <= R4_SYNTHETIC_ROUNDOFF_BOUND;
    }

    private static boolean samePeakBand(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak first,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak second) {
        return first.syntheticCenter() == second.syntheticCenter()
            && sameSyntheticRoundoff(first.offsetPx(), second.offsetPx())
            && sameSyntheticRoundoff(first.intensity(), second.intensity())
            && sameSyntheticRoundoff(first.supportWidthPx(), second.supportWidthPx())
            && sameSyntheticRoundoff(first.prominence(), second.prominence())
            && sameSyntheticRoundoff(first.noiseFloor(), second.noiseFloor())
            && sameSyntheticRoundoff(first.maxProfileIntensity(), second.maxProfileIntensity())
            && sameSyntheticRoundoff(first.gradientStrength(), second.gradientStrength())
            && sameSyntheticRoundoff(first.gradientBalance(), second.gradientBalance())
            && sameSyntheticRoundoff(first.nativeFilteredAgreement(), second.nativeFilteredAgreement())
            && sameSyntheticRoundoff(first.rawCenterPx(), second.rawCenterPx())
            && sameSyntheticRoundoff(first.lightFilteredCenterPx(), second.lightFilteredCenterPx())
            && sameSyntheticRoundoff(first.standardFilteredCenterPx(), second.standardFilteredCenterPx())
            && sameSyntheticRoundoff(first.scaleOffsetRmsPx(), second.scaleOffsetRmsPx())
            && sameSyntheticRoundoff(first.scaleAgreement(), second.scaleAgreement())
            && sameSyntheticRoundoff(first.centerUncertaintyPx(), second.centerUncertaintyPx());
    }

    private static boolean isCensored(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile profile) {
        return !profile.anchorWithinRaster() || profile.intensitySamples().stream().anyMatch(sample -> !sample.insideRaster());
    }

    private static boolean isUnsupported(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().isEmpty() || profile.peaks().stream().allMatch(peak -> peak.syntheticCenter()
            && peak.supportWidthPx() <= 0.0 && peak.prominence() <= 0.0 && peak.maxProfileIntensity() <= 0.0);
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
        binding.requireNoTerminalFailure();
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
