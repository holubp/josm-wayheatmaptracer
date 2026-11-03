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
    public record PeakOwner(int originalPeakId, CrossSectionPeak peak) {
        public PeakOwner {
            if (originalPeakId < 0 || peak == null) {
                throw new IllegalArgumentException("Legacy peak ownership is incomplete");
            }
        }
    }

    /** Original Legacy source row and its exact retained producer profile. */
    public record OriginalObservation(
        int originalRow,
        int retainedObservationIndex,
        CrossSectionProfile profile,
        SourceClass sourceClass,
        List<PeakOwner> producerPeaks
    ) {
        public OriginalObservation {
            if (originalRow < 0 || retainedObservationIndex < 0 || profile == null || sourceClass == null) {
                throw new IllegalArgumentException("Legacy original observation is incomplete");
            }
            producerPeaks = List.copyOf(Objects.requireNonNull(producerPeaks, "producer peaks"));
            int previousId = -1;
            for (PeakOwner owner : producerPeaks) {
                if (owner.originalPeakId() <= previousId
                        || owner.originalPeakId() >= profile.peaks().size()
                        || profile.peaks().get(owner.originalPeakId()) != owner.peak()) {
                    throw new IllegalArgumentException("Legacy peak IDs must retain original producer identity and order");
                }
                previousId = owner.originalPeakId();
            }
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
    private final java.util.Optional<ProductionAttestation> productionAttestation;
    /** No production producer/selection attestation is available through this structural factory. */
    private final boolean productionEligible;
    private final AttemptMemoryLedger.Reservation retainedMetadata;

    LegacyEmissionBinding(
        OptionalEmissionContextFactory.SourceBinding sourcePermit,
        MultiScaleProfileSet producedProfiles,
        List<Integer> originalRowToRetainedObservation,
        List<OriginalObservation> observations,
        double trackerNormalizationRasterPx,
        List<ProtectedOccurrence> protectedOccurrences,
        AttemptMemoryLedger.Reservation retainedMetadata
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
        this.producedProfiles = producedProfiles;
        this.origin = sourcePermit.origin();
        this.productionAttestation = java.util.Optional.empty();
        this.productionEligible = productionAttestation.isPresent()
            && origin == OptionalEmissionContext.Origin.DETACHED_FROZEN;
        this.retainedMetadata = retainedMetadata;
        validateRowSequence();
        validateProtectedOccurrences();
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
            double left = sourceChainageOriginMeters
                + localChainage(observations.get(occurrence.leftOriginalRow()).profile());
            double right = sourceChainageOriginMeters
                + localChainage(observations.get(occurrence.rightOriginalRow()).profile());
            if (occurrence.sourceChainageMeters() < left || occurrence.sourceChainageMeters() > right) {
                throw new IllegalArgumentException("Legacy protected occurrence is outside its original-row bracket");
            }
            if (Double.doubleToLongBits(occurrence.sourceChainageMeters()) == Double.doubleToLongBits(left)) {
                requireExactPosition(occurrence, observations.get(occurrence.leftOriginalRow()).profile());
            }
            if (Double.doubleToLongBits(occurrence.sourceChainageMeters()) == Double.doubleToLongBits(right)) {
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
                    || observation.profile() != retained.profiles().levelZeroProfiles().get(index)
                    || observation.profile().samplingAnchor() != retained.profileLocations().get(index)) {
                throw new IllegalArgumentException("Legacy binding row does not retain its original producer profile");
            }
            OptionalEmissionContext.Observation fact = context.observations().get(index);
            if (fact.index() != index || !fact.location().equals(observation.profile().samplingAnchor())
                    || Double.doubleToLongBits(fact.normal().x())
                        != Double.doubleToLongBits(observation.profile().normalScreen().x)
                    || Double.doubleToLongBits(fact.normal().y())
                        != Double.doubleToLongBits(observation.profile().normalScreen().y)
                    || Double.doubleToLongBits(fact.location().cumulativeGroundDistanceMeters())
                        != Double.doubleToLongBits(observation.profile().cumulativeGroundDistanceMeters())) {
                throw new IllegalArgumentException("Legacy binding row differs from retained context coordinates");
            }
            validateSourceClass(observation);
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
    public double trackerNormalizationRasterPx() { return trackerNormalizationRasterPx; }
    public List<ProtectedOccurrence> protectedOccurrences() { return protectedOccurrences; }
    public OptionalEmissionContext.Origin origin() { return origin; }
    /** Empty until a trusted production adapter attests actual tracker/selection state. */
    public java.util.Optional<ProductionAttestation> productionAttestation() { return productionAttestation; }
    public boolean productionEligible() { return productionEligible; }
}
