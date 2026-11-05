package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.SourceClass;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Immutable original-row ownership for an opt-in Legacy emission attempt. */
public final class LegacyEmissionBinding {
    /** Typed proof slot reserved for a future trusted sampler/tracker adapter. */
    public static final class ProductionAttestation {
        private ProductionAttestation() { }
    }

    /** One producer peak reference with its unmodified native enumeration ID. */
    public record PeakOwner(int originalPeakId, CrossSectionPeak peak, CrossSectionPeak measurementPeakOwner) {
        public PeakOwner {
            if (originalPeakId < 0 || peak == null || measurementPeakOwner == null) {
                throw new IllegalArgumentException("Legacy peak ownership is incomplete");
            }
        }

        public PeakOwner(int originalPeakId, CrossSectionPeak peak) {
            this(originalPeakId, peak, peak);
        }
    }

    /** Original Legacy source row and its exact retained producer profile. */
    public record OriginalObservation(
        int originalRow,
        int retainedObservationIndex,
        CrossSectionProfile profile,
        CrossSectionProfile measurementProfile,
        SourceClass sourceClass,
        List<PeakOwner> producerPeaks
    ) {
        public OriginalObservation {
            if (originalRow < 0 || retainedObservationIndex < 0 || profile == null || measurementProfile == null
                    || sourceClass == null) {
                throw new IllegalArgumentException("Legacy original observation is incomplete");
            }
            producerPeaks = List.copyOf(Objects.requireNonNull(producerPeaks, "producer peaks"));
            int previousId = -1;
            for (PeakOwner owner : producerPeaks) {
                if (owner.originalPeakId() <= previousId
                        || owner.originalPeakId() >= profile.peaks().size()
                        || profile.peaks().get(owner.originalPeakId()) != owner.peak()
                        || measurementProfile.peaks().stream()
                            .noneMatch(candidate -> candidate == owner.measurementPeakOwner())) {
                    throw new IllegalArgumentException("Legacy peak IDs must retain original producer identity and order");
                }
                previousId = owner.originalPeakId();
            }
        }

        public OriginalObservation(int originalRow, int retainedObservationIndex, CrossSectionProfile profile,
                SourceClass sourceClass, List<PeakOwner> producerPeaks) {
            this(originalRow, retainedObservationIndex, profile, profile, sourceClass, producerPeaks);
        }
    }

    /** Exact mandatory protected occurrence, separate from any sampled profile or emitted vertex. */
    public record ProtectedOccurrence(
        String occurrenceId,
        int leftOriginalRow,
        int rightOriginalRow,
        double sourceChainageMeters,
        MetricPoint exactPosition,
        boolean fixed
    ) {
        public ProtectedOccurrence {
            if (occurrenceId == null || occurrenceId.isBlank() || leftOriginalRow < 0
                    || rightOriginalRow != leftOriginalRow + 1
                    || !Double.isFinite(sourceChainageMeters) || exactPosition == null) {
                throw new IllegalArgumentException("Legacy protected occurrence is incomplete");
            }
        }
    }

    private final List<Integer> originalRowToRetainedObservation;
    private final List<OriginalObservation> observations;
    private final double trackerNormalizationRasterPx;
    private final List<ProtectedOccurrence> protectedOccurrences;
    private final double sourceChainageOriginMeters;
    private final OptionalEmissionContextFactory.SourceBinding sourcePermit;
    private final MultiScaleProfileSet producedProfiles;
    private final OptionalEmissionContext.Origin origin;
    private final OptionalEmissionContext managedContext;
    private final java.util.Optional<ProductionAttestation> productionAttestation;
    /** No production producer/selection attestation is available through this structural factory. */
    private final boolean productionEligible;
    private final AttemptMemoryLedger.Reservation retainedMetadata;
    private final DeclaredSyntheticRasterReceipt syntheticRasterReceipt;
    private final OptionalEmissionContextFactory.ManagedCaptureBinding managedPermit;
    private final org.openstreetmap.josm.plugins.wayheatmaptracer.service.SourceParameterCertificate managedSourceParameters;
    private SampledEmissionInput managedInput;

    LegacyEmissionBinding(
        OptionalEmissionContextFactory.SourceBinding sourcePermit,
        MultiScaleProfileSet producedProfiles,
        List<Integer> originalRowToRetainedObservation,
        List<OriginalObservation> observations,
        double trackerNormalizationRasterPx,
        List<ProtectedOccurrence> protectedOccurrences,
        AttemptMemoryLedger.Reservation retainedMetadata
    ) {
        this(sourcePermit, producedProfiles, originalRowToRetainedObservation, observations,
            trackerNormalizationRasterPx, protectedOccurrences, retainedMetadata, null);
    }

    LegacyEmissionBinding(
        OptionalEmissionContextFactory.SourceBinding sourcePermit,
        MultiScaleProfileSet producedProfiles,
        List<Integer> originalRowToRetainedObservation,
        List<OriginalObservation> observations,
        double trackerNormalizationRasterPx,
        List<ProtectedOccurrence> protectedOccurrences,
        AttemptMemoryLedger.Reservation retainedMetadata,
        DeclaredSyntheticRasterReceipt syntheticRasterReceipt
    ) {
        if (sourcePermit == null || producedProfiles == null || originalRowToRetainedObservation == null
                || observations == null || originalRowToRetainedObservation.isEmpty()
                || originalRowToRetainedObservation.size() != observations.size()
                || !Double.isFinite(trackerNormalizationRasterPx) || trackerNormalizationRasterPx <= 0.0
                || protectedOccurrences == null || retainedMetadata == null) {
            throw new IllegalArgumentException("Legacy emission binding is incomplete");
        }
        this.originalRowToRetainedObservation = List.copyOf(originalRowToRetainedObservation);
        this.observations = List.copyOf(observations);
        this.trackerNormalizationRasterPx = trackerNormalizationRasterPx;
        this.protectedOccurrences = List.copyOf(protectedOccurrences);
        this.sourceChainageOriginMeters = sourcePermit.chainage().sourceOriginGroundMeters();
        this.sourcePermit = sourcePermit;
        this.managedPermit = null;
        this.managedContext = null;
        this.managedSourceParameters = null;
        this.producedProfiles = producedProfiles;
        this.origin = sourcePermit.origin();
        this.productionAttestation = java.util.Optional.empty();
        this.productionEligible = productionAttestation.isPresent()
            && origin == OptionalEmissionContext.Origin.DETACHED_FROZEN;
        this.retainedMetadata = retainedMetadata;
        this.syntheticRasterReceipt = syntheticRasterReceipt;
        validateRowSequence();
        validateProtectedOccurrences();
        this.nativeProfileSnapshots = syntheticRasterReceipt == null ? List.of()
            : observations.stream().map(NativeProfileSnapshot::capture).toList();
    }

    private LegacyEmissionBinding(OptionalEmissionContextFactory.ManagedCaptureBinding permit,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RetainedManagedScalarProfiles retained,
            OptionalEmissionContext context, AttemptMemoryLedger.Reservation metadata) {
        sourcePermit = null;
        managedPermit = permit;
        managedContext = context;
        managedSourceParameters = retained.sourceParameters().orElseThrow();
        producedProfiles = retained.profileSet();
        origin = OptionalEmissionContext.Origin.MANAGED_CAPTURE;
        trackerNormalizationRasterPx = permit.capture().trackerNormalizationRasterPx();
        sourceChainageOriginMeters = 0.0;
        List<Integer> mapping = new java.util.ArrayList<>();
        List<OriginalObservation> rows = new java.util.ArrayList<>();
        for (int row = 0; row < retained.profiles().size(); row++) {
            permit.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var profile = retained.profiles().get(row);
            List<PeakOwner> peaks = new java.util.ArrayList<>();
            for (int peak = 0; peak < profile.peaks().size(); peak++) {
                permit.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                peaks.add(new PeakOwner(peak, profile.peaks().get(peak)));
            }
            boolean censored = !profile.anchorWithinRaster()
                || profile.intensitySamples().stream().anyMatch(sample -> !sample.insideRaster());
            SourceClass sourceClass = censored ? SourceClass.CENSORED
                : isUnsupportedProfile(profile) ? SourceClass.GAP : SourceClass.OBSERVED;
            mapping.add(row);
            rows.add(new OriginalObservation(row, row, profile, sourceClass, peaks));
        }
        originalRowToRetainedObservation = List.copyOf(mapping);
        observations = List.copyOf(rows);
        List<ProtectedOccurrence> events = new java.util.ArrayList<>();
        for (var occurrence : managedSourceParameters.originalOccurrences()) {
            permit.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (occurrence.protectedOccurrence()) events.add(new ProtectedOccurrence(
                occurrence.occurrenceId(), occurrence.leftRow(), occurrence.rightRow(),
                occurrence.sourceChainageMeters(), occurrence.exactMetric(), true));
        }
        protectedOccurrences = List.copyOf(events);
        if (events.size() < 2 || !context.productionEligible()) {
            throw new IllegalArgumentException("Managed mandatory occurrence authority is incomplete");
        }
        retainedMetadata = metadata;
        syntheticRasterReceipt = null;
        productionAttestation = java.util.Optional.of(new ProductionAttestation());
        productionEligible = true;
        validateRowSequence();
        validateProtectedOccurrences();
        nativeProfileSnapshots = observations.stream()
            .map(observation -> NativeProfileSnapshot.capture(observation, true)).toList();
        context.requireProfilesUnchanged(producedProfiles);
    }

    static LegacyEmissionBinding fromManagedCapture(
            OptionalEmissionContextFactory.ManagedCaptureBinding permit,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RetainedManagedScalarProfiles retained,
            OptionalEmissionContext context) {
        permit.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        long peaks = 0;
        for (var profile : retained.profiles()) {
            permit.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            peaks = Math.addExact(peaks, profile.peaks().size());
        }
        // The existing per-row metadata reservation also owns the native immutable-list certificate fields.
        var reservation = permit.retainedOwner().reserve(Math.addExact(8192L,
            Math.addExact(Math.multiplyExact((long) retained.profiles().size(), 1024L),
                Math.multiplyExact(peaks, 96L))));
        try {
            var result = new LegacyEmissionBinding(permit, retained, context, reservation);
            reservation.adopt(result);
            return result;
        } catch (RuntimeException failure) { reservation.close(); throw failure; }
    }

    private final List<NativeProfileSnapshot> nativeProfileSnapshots;

    private record NativeProfileSnapshot(
        CrossSectionProfile profile,
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.ProfileSamplingLocation anchor,
        double anchorX, double anchorY, double normalX, double normalY, double chainage,
        List<CrossSectionPeak> peaks,
        List<CrossSectionPeak> immutablePeakList,
        List<RenderedHeatmapSampler.IntensitySample> immutableIntensitySamples,
        boolean anchorWithinRaster,
        SourceClass immutableSourceClass,
        boolean immutableCertificate
    ) {
        private static NativeProfileSnapshot capture(OriginalObservation observation) {
            return capture(observation, false);
        }

        private static NativeProfileSnapshot capture(OriginalObservation observation, boolean managed) {
            CrossSectionProfile profile = observation.profile();
            boolean certified = managed && profile == observation.measurementProfile()
                && profile.samplingAnchor().getClass()
                    == org.openstreetmap.josm.plugins.wayheatmaptracer.service.ProfileSamplingAnchor.class;
            return new NativeProfileSnapshot(profile, profile.samplingAnchor(), profile.anchorScreen().x,
                profile.anchorScreen().y, profile.normalScreen().x, profile.normalScreen().y,
                profile.cumulativeGroundDistanceMeters(), List.copyOf(profile.peaks()),
                certified ? profile.peaks() : null, certified ? profile.intensitySamples() : null,
                profile.anchorWithinRaster(), certified ? observation.sourceClass() : null, certified);
        }

        private boolean unchanged(OriginalObservation observation) {
            if (profile != observation.profile() || profile.samplingAnchor() != anchor
                    || Double.doubleToLongBits(profile.anchorScreen().x) != Double.doubleToLongBits(anchorX)
                    || Double.doubleToLongBits(profile.anchorScreen().y) != Double.doubleToLongBits(anchorY)
                    || Double.doubleToLongBits(profile.normalScreen().x) != Double.doubleToLongBits(normalX)
                    || Double.doubleToLongBits(profile.normalScreen().y) != Double.doubleToLongBits(normalY)
                    || Double.doubleToLongBits(profile.cumulativeGroundDistanceMeters())
                        != Double.doubleToLongBits(chainage)) return false;
            if (immutableCertificate) {
                return profile.peaks() == immutablePeakList
                    && profile.intensitySamples() == immutableIntensitySamples
                    && profile.anchorWithinRaster() == anchorWithinRaster
                    && observation.sourceClass() == immutableSourceClass;
            }
            if (profile.peaks().size() != peaks.size()) return false;
            for (int index = 0; index < peaks.size(); index++) {
                if (profile.peaks().get(index) != peaks.get(index)) return false;
            }
            return true;
        }
    }

    /** Compact validated declaration retained for synthetic R4 diagnostics only. */
    record DeclaredSyntheticRasterReceipt(
        int fullWidth, int fullHeight, int cropOriginX, int cropOriginY,
        double nativePitchRasterPixels, double chainageOriginMeters, int sourceIdentity,
        List<ProtectedOccurrence> protectedOccurrences
    ) {
        DeclaredSyntheticRasterReceipt {
            protectedOccurrences = List.copyOf(protectedOccurrences);
        }
    }

    private void validateRowSequence() {
        int previousRetained = -1;
        for (int row = 0; row < observations.size(); row++) {
            OriginalObservation observation = observations.get(row);
            int retainedIndex = originalRowToRetainedObservation.get(row);
            if (observation.originalRow() != row || observation.retainedObservationIndex() != retainedIndex
                    || retainedIndex <= previousRetained) {
                throw new IllegalArgumentException("Original Legacy rows require an ordered one-to-one retained mapping");
            }
            previousRetained = retainedIndex;
        }
    }

    private void validateProtectedOccurrences() {
        String previousId = null;
        ProtectedOccurrence previous = null;
        double previousChainage = Double.NEGATIVE_INFINITY;
        Set<String> uniqueIds = new HashSet<>();
        for (ProtectedOccurrence occurrence : protectedOccurrences) {
            if (occurrence.leftOriginalRow() < 0 || occurrence.rightOriginalRow() >= observations.size()
                    || occurrence.sourceChainageMeters() < previousChainage
                    || occurrence.occurrenceId().equals(previousId)
                    || !uniqueIds.add(occurrence.occurrenceId())) {
                throw new IllegalArgumentException("Legacy protected occurrences must remain ordered and bracketed");
            }
            if (previous != null
                    && occurrence.sourceChainageMeters() == previous.sourceChainageMeters()
                    && !occurrence.exactPosition().equals(previous.exactPosition())) {
                throw new IllegalArgumentException("Equal-chainage protected occurrences conflict in exact position");
            }
            double left = managedSourceParameters == null ? sourceChainageOriginMeters
                + localChainage(observations.get(occurrence.leftOriginalRow()).profile())
                : managedSourceParameters.sourceChainageMeters(occurrence.leftOriginalRow());
            double right = managedSourceParameters == null ? sourceChainageOriginMeters
                + localChainage(observations.get(occurrence.rightOriginalRow()).profile())
                : managedSourceParameters.sourceChainageMeters(occurrence.rightOriginalRow());
            if (occurrence.sourceChainageMeters() < left || occurrence.sourceChainageMeters() > right) {
                throw new IllegalArgumentException("Legacy protected occurrence is outside its original-row bracket");
            }
            if (managedSourceParameters == null && Double.doubleToLongBits(occurrence.sourceChainageMeters()) == Double.doubleToLongBits(left)) {
                requireExactPosition(occurrence, observations.get(occurrence.leftOriginalRow()).profile());
            }
            if (managedSourceParameters == null && Double.doubleToLongBits(occurrence.sourceChainageMeters()) == Double.doubleToLongBits(right)) {
                requireExactPosition(occurrence, observations.get(occurrence.rightOriginalRow()).profile());
            }
            previousId = occurrence.occurrenceId();
            previousChainage = occurrence.sourceChainageMeters();
            previous = occurrence;
        }
    }

    private static void requireExactPosition(ProtectedOccurrence occurrence, CrossSectionProfile profile) {
        if (profile.samplingAnchor() instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation detached
                && !detached.metricPoint().equals(occurrence.exactPosition())) {
            throw new IllegalArgumentException("Coincident protected event must match its exact profile coordinate");
        }
    }

    private static double localChainage(CrossSectionProfile profile) {
        return profile.cumulativeGroundDistanceMeters();
    }

    void validatePublication(
        OptionalEmissionContextFactory.SourceBinding permit,
        RenderedHeatmapSampler.RetainedScalarProfiles retained,
        OptionalEmissionContext context
    ) {
        if (sourcePermit != permit || producedProfiles != retained.profiles() || origin != context.origin()) {
            throw new IllegalArgumentException("Legacy binding belongs to a different retained producer");
        }
        context.requireProfilesUnchanged(retained.profiles());
        for (OriginalObservation observation : observations) {
            int index = observation.retainedObservationIndex();
            if (index >= context.observations().size() || index >= retained.profileLocations().size()
                    || observation.measurementProfile() != retained.profiles().levelZeroProfiles().get(index)
                    || observation.measurementProfile().samplingAnchor() != retained.profileLocations().get(index)) {
                throw new IllegalArgumentException("Legacy binding row does not retain its measurement producer profile");
            }
            if (syntheticRasterReceipt == null && observation.profile() != observation.measurementProfile()) {
                throw new IllegalArgumentException("Strict producer binding must preserve profile object identity");
            }
            OptionalEmissionContext.Observation fact = context.observations().get(index);
            if (fact.index() != index || !fact.location().equals(observation.measurementProfile().samplingAnchor())
                    || Double.doubleToLongBits(fact.normal().x())
                        != Double.doubleToLongBits(observation.measurementProfile().normalScreen().x)
                    || Double.doubleToLongBits(fact.normal().y())
                        != Double.doubleToLongBits(observation.measurementProfile().normalScreen().y)
                    || Double.doubleToLongBits(fact.location().cumulativeGroundDistanceMeters())
                        != Double.doubleToLongBits(observation.measurementProfile().cumulativeGroundDistanceMeters())) {
                throw new IllegalArgumentException("Legacy binding row differs from retained context coordinates");
            }
            validateSourceClass(observation);
        }
        requireNativeProfilesUnchanged(sourcePermit.budget());
    }

    void requireNativeProfilesUnchanged(EmissionWorkBudget budget) {
        requireNativeProfilesUnchanged(budget, null, false);
    }

    void requireNativeProfilesUnchanged(EmissionWorkBudget budget, SampledEmissionInput caller) {
        requireNativeProfilesUnchanged(budget, caller, true);
    }

    private void requireNativeProfilesUnchanged(EmissionWorkBudget budget, SampledEmissionInput caller,
            boolean allowManagedCertificate) {
        if (allowManagedCertificate) requireInputIdentity(caller);
        if (managedPermit != null) managedPermit.requireCurrent();
        if (syntheticRasterReceipt == null && managedPermit == null) return;
        for (int index = 0; index < observations.size(); index++) {
            NativeProfileSnapshot snapshot = nativeProfileSnapshots.get(index);
            boolean useCertificate = allowManagedCertificate && snapshot.immutableCertificate();
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,
                useCertificate ? 1 : 1L + snapshot.peaks().size());
            if (!snapshot.unchanged(observations.get(index))) {
                throw new IllegalStateException("Original Legacy profile changed after binding publication");
            }
            if (!useCertificate) validateSourceClass(observations.get(index));
        }
    }

    synchronized void attachManagedInput(SampledEmissionInput input) {
        if (managedPermit == null) return;
        Objects.requireNonNull(input, "input");
        if (managedInput != null) throw new IllegalStateException("Managed Legacy binding is already attached to an input");
        if (input.profiles() != producedProfiles || input.context() != managedContext
                || input.workBudget() != managedPermit.workBudget()
                || input.legacyBinding().orElse(null) != this) {
            throw new IllegalArgumentException("Managed Legacy binding cannot attach to a foreign input");
        }
        managedInput = input;
    }

    void requireInputIdentity(SampledEmissionInput input) {
        if (managedPermit != null && (input == null || managedInput != input)) {
            throw new IllegalStateException("Managed Legacy binding belongs to a different retained input");
        }
    }
    long finalPublicationWork(){
        long work=32;
        for(var observation:observations)work=Math.addExact(work,8L+observation.profile().intensitySamples().size()+observation.producerPeaks().size());
        return work;
    }
    /** Callback-free scan admitted by the owning input's final publication ticket. */
    void requireNativeProfilesUnchangedForFinalPublication(){
        if(syntheticRasterReceipt==null&&managedPermit==null)return;
        for(int i=0;i<observations.size();i++){
            if(!nativeProfileSnapshots.get(i).unchanged(observations.get(i)))throw new IllegalStateException("Original Legacy profile changed after binding publication");
            validateSourceClass(observations.get(i));
        }
    }

    private static void validateSourceClass(OriginalObservation observation) {
        boolean censored = !observation.profile().anchorWithinRaster()
            || observation.profile().intensitySamples().stream().anyMatch(sample -> !sample.insideRaster());
        boolean unsupported = isUnsupportedProfile(observation.profile());
        SourceClass expected = censored ? SourceClass.CENSORED
            : unsupported ? SourceClass.GAP : SourceClass.OBSERVED;
        if (observation.sourceClass() != expected) {
            throw new IllegalArgumentException("Legacy source class does not match its retained producer evidence");
        }
    }

    private static boolean isUnsupportedProfile(CrossSectionProfile profile) {
        return profile.peaks().isEmpty() || profile.peaks().stream().allMatch(LegacyEmissionBinding::isUnsupportedFallbackPeak);
    }

    private static boolean isUnsupportedFallbackPeak(CrossSectionPeak peak) {
        return peak.syntheticCenter() && peak.supportWidthPx() <= 0.0 && peak.prominence() <= 0.0
            && peak.maxProfileIntensity() <= 0.0;
    }

    void adoptRetainedMetadata() {
        retainedMetadata.adopt(this);
    }

    public List<Integer> originalRowToRetainedObservation() { return originalRowToRetainedObservation; }
    public List<OriginalObservation> observations() { return observations; }
    DeclaredSyntheticRasterReceipt syntheticRasterReceipt() { return syntheticRasterReceipt; }
    public double trackerNormalizationRasterPx() { return trackerNormalizationRasterPx; }
    public List<ProtectedOccurrence> protectedOccurrences() { return protectedOccurrences; }
    public OptionalEmissionContext.Origin origin() { return origin; }
    /** Empty until a trusted production adapter attests actual tracker/selection state. */
    public java.util.Optional<ProductionAttestation> productionAttestation() { return productionAttestation; }
    public boolean productionEligible() { return productionEligible; }
}
