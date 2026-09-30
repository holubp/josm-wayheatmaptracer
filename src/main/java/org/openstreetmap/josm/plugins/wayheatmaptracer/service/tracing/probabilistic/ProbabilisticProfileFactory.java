package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.LongConsumer;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ImageOrientationDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.StrictScalarSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Samples full scalar cross-sections and extracts deterministic finite observation hypotheses. */
public final class ProbabilisticProfileFactory {
    /**
     * Computes the exact profile chainage used by deterministic resampling.
     *
     *  sourcePolyline selected way geometry in the snapshot metric frame
     *  configuredStepMeters desired longitudinal step in ground metres
     *  immutable request-owned chainage accepted by the profile sampler
     */
    public org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage(
            List<MetricPoint> sourcePolyline, double configuredStepMeters) {
        if (sourcePolyline == null || sourcePolyline.size() < 2 || !positive(configuredStepMeters)) {
            throw new IllegalArgumentException("Profile chainage inputs are incomplete");
        }
        ResampledCurve curve = resample(sourcePolyline, configuredStepMeters, null);
        return new org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage(
                curve.chainageMeters(), configuredStepMeters);
    }

    /**
     * Samples complete physical profiles along an immutable source polyline.
     *
     * @param sourcePolyline selected way geometry in the snapshot metric frame
     * @param configuredStepMeters desired longitudinal step in ground metres
     * @param searchHalfWidthMeters ordinary authorized search radius
     * @param fixedEndpoints whether first and last source positions remain exact
     * @param evidence immutable scalar snapshot
     * @param field selected scalar evidence field
     * @return ordered profiles independent of Corridor A membership
     */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field) {
        return create(sourcePolyline, configuredStepMeters, searchHalfWidthMeters, fixedEndpoints,
            evidence, field, EvidenceModelParameters.defaults(), CancellationProbe.NONE);
    }

    /** Samples profiles with a versioned localization policy and cooperative cancellation. */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field, EvidenceModelParameters parameters,
        CancellationProbe cancellation) {
        return create(sourcePolyline, configuredStepMeters, searchHalfWidthMeters,
                fixedEndpoints, evidence, field, parameters, cancellation, true);
    }

    List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field, EvidenceModelParameters parameters,
        CancellationProbe cancellation, boolean directLongitudinalReliability) {
        return create(sourcePolyline, configuredStepMeters, searchHalfWidthMeters, fixedEndpoints,
                evidence, field, parameters, cancellation, directLongitudinalReliability, 0.0, null);
    }

    private List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field, EvidenceModelParameters parameters,
        CancellationProbe cancellation, boolean directLongitudinalReliability,
        double sourceOriginGroundMeters, AttemptMemoryLedger.Owner owner) {
        if (sourcePolyline == null || sourcePolyline.size() < 2 || !positive(configuredStepMeters)
            || !positive(searchHalfWidthMeters) || evidence == null || field == null
            || parameters == null || cancellation == null
            || !Double.isFinite(sourceOriginGroundMeters) || sourceOriginGroundMeters < 0.0) {
            throw new IllegalArgumentException("Profile sampling inputs are incomplete");
        }
        AttemptMemoryLedger.Owner samplingOwner = owner == null ? null
                : owner.child("profile-sampling-temporaries");
        AttemptMemoryLedger.Owner rawOwner = owner == null ? null : owner.child("sampled-profiles");
        ResampledCurve curve = resample(sourcePolyline, configuredStepMeters, samplingOwner);
        ImageOrientationDescriptor orientationDescriptor = new ImageOrientationDescriptor();
        LocalScalarProfileExtractor profileExtractor = new LocalScalarProfileExtractor();
        AttemptMemoryLedger.Owner rawListOwner = rawOwner == null ? null
                : rawOwner.child("profile-list-builder");
        List<ProbabilisticProfile> result = rawListOwner == null
                ? new ArrayList<>(curve.points().size())
                : ProbabilisticInference.allocated(rawListOwner,
                    ProbabilisticInference.listBytes(curve.points().size()), "sampled profiles",
                    () -> new ArrayList<>(curve.points().size()));
        for (int index = 0; index < curve.points().size(); index++) {
            cancellation.checkpoint();
            AttemptMemoryLedger.Owner profileTemporary = samplingOwner == null ? null
                    : samplingOwner.child("profile-" + index);
            double sourcePitch = evidence.resolution().effectivePitchMetersAt(
                    sourceOriginGroundMeters + curve.chainageMeters().get(index));
            double samplePitch = 0.5 * sourcePitch;
            MetricPoint anchor = curve.points().get(index);
            int sampledIndex = index;
            if (rawOwner != null) rawOwner.retain(anchor);
            MetricPoint tangent = profileTemporary == null ? tangent(curve.points(), index)
                    : ProbabilisticInference.allocated(profileTemporary,
                        AttemptMemoryLedger.objectBytes(16), "sampled profiles",
                        () -> tangent(curve.points(), sampledIndex));
            MetricPoint normal = rawOwner == null
                    ? new MetricPoint(-tangent.yMeters(), tangent.xMeters())
                    : ProbabilisticInference.allocated(rawOwner,
                        AttemptMemoryLedger.objectBytes(16), "sampled profiles",
                        () -> new MetricPoint(-tangent.yMeters(), tangent.xMeters()));
            double minimum = authorizedBoundary(anchor, normal, -1.0, searchHalfWidthMeters,
                samplePitch, evidence);
            double maximum = authorizedBoundary(anchor, normal, 1.0, searchHalfWidthMeters,
                samplePitch, evidence);
            if (maximum - minimum < 1e-9) {
                minimum = -Math.min(samplePitch, searchHalfWidthMeters);
                maximum = Math.min(samplePitch, searchHalfWidthMeters);
            }
            List<ProbabilisticProfile.Sample> samples = sampleProfile(anchor, normal, minimum,
                maximum, samplePitch, evidence, field, profileTemporary, rawOwner);
            List<LocalScalarProfileExtractor.Sample> extractorSamples = profileTemporary == null
                    ? samples.stream().map(sample -> new LocalScalarProfileExtractor.Sample(
                        sample.offsetMeters(), sample.intensity(), sample.valid())).toList()
                    : ProbabilisticInference.allocated(profileTemporary,
                        ProbabilisticInference.listBytes(samples.size())
                            + Math.multiplyExact(samples.size(),
                                AttemptMemoryLedger.objectBytes(24)),
                        "sampled profiles", () -> samples.stream()
                            .map(sample -> new LocalScalarProfileExtractor.Sample(
                                sample.offsetMeters(), sample.intensity(), sample.valid())).toList());
            LocalScalarProfileExtractor.Result scalarFeatures = profileExtractor.extract(
                    extractorSamples, sourcePitch, parameters.localization(), profileTemporary);
            ExtractedModes extracted = adaptModes(scalarFeatures, index, profileTemporary, rawOwner);
            List<ProbabilisticProfile.Mode> orientedModes = bindOrientationToModes(
                extracted.modes(), anchor, normal, sourcePitch, evidence, field, parameters,
                cancellation, orientationDescriptor, directLongitudinalReliability,
                profileTemporary, rawOwner);
            ImageOrientationSupport orientation = aggregateOrientation(orientedModes, rawOwner);
            OptionalDouble exact = fixedEndpoints && (index == 0 || index == curve.points().size() - 1)
                ? rawOwner == null ? OptionalDouble.of(0.0)
                    : ProbabilisticInference.allocated(rawOwner,
                        AttemptMemoryLedger.objectBytes(16), "sampled profiles",
                        () -> OptionalDouble.of(0.0))
                : OptionalDouble.empty();
            int profileIndex = index;
            double chainage = curve.chainageMeters().get(index);
            double retainedMinimum = minimum;
            double retainedMaximum = maximum;
            SupplierProfile allocation = () -> new ProbabilisticProfile(profileIndex, chainage,
                    anchor, normal, retainedMinimum, retainedMaximum, sourcePitch,
                    evidence.resolution().nativePitchMeters().isPresent(), scalarFeatures.noiseFloor(),
                    samples, orientedModes, extracted.censoredModes(), exact, orientation);
            result.add(rawOwner == null ? allocation.get()
                    : ownedProfile(rawOwner, samples.size(), allocation));
            if (profileTemporary != null) profileTemporary.close();
        }
        if (samplingOwner != null) samplingOwner.close();
        if (directLongitudinalReliability) {
            AttemptMemoryLedger.Owner intervalOwner = owner == null ? null
                    : owner.child("reliability-raster-intervals");
            try {
                List<ProbabilisticProfile> reliable = LongitudinalModeReliability.apply(result,
                        new RasterIntervalEvidence(evidence, field, intervalOwner), cancellation,
                        owner).profiles();
                if (rawOwner != null) rawOwner.close();
                return reliable;
            } finally {
                if (intervalOwner != null) intervalOwner.close();
            }
        }
        List<ProbabilisticProfile> copied = rawOwner == null ? List.copyOf(result)
                : ProbabilisticInference.allocated(rawOwner,
                    ProbabilisticInference.listBytes(result.size()), "sampled profiles",
                    () -> List.copyOf(result));
        if (rawOwner != null) {
            rawListOwner.close();
            rawOwner.transferTo(owner);
            rawOwner.close();
        }
        return copied;
    }

    /** Samples profiles and proves that engine sampling matches the request-owned measured chainage. */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field) {
        return create(sourcePolyline, profileChainage, searchHalfWidthMeters, fixedEndpoints,
            evidence, field, EvidenceModelParameters.defaults(), CancellationProbe.NONE);
    }

    /** Samples a request-owned chainage with versioned localization and cancellation. */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field, EvidenceModelParameters parameters, CancellationProbe cancellation) {
        return create(sourcePolyline, profileChainage, searchHalfWidthMeters,
                fixedEndpoints, evidence, field, parameters, cancellation, true);
    }

    List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field, EvidenceModelParameters parameters, CancellationProbe cancellation,
        boolean directLongitudinalReliability) {
        if (profileChainage == null) {
            throw new IllegalArgumentException("Measured profile chainage is required");
        }
        List<ProbabilisticProfile> result = create(sourcePolyline, profileChainage.configuredStepMeters(),
            searchHalfWidthMeters, fixedEndpoints, evidence, field, parameters, cancellation,
            directLongitudinalReliability, profileChainage.sourceOriginGroundMeters(), null);
        if (result.size() != profileChainage.cumulativeGroundMeters().size()) {
            throw new IllegalArgumentException("Request chainage does not match deterministic profile sampling");
        }
        for (int index = 0; index < result.size(); index++) {
            if (Math.abs(result.get(index).chainageMeters()
                - profileChainage.cumulativeGroundMeters().get(index)) > 1e-8) {
                throw new IllegalArgumentException("Request chainage differs from sampled profile anchors");
            }
        }
        return result;
    }

    List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field, EvidenceModelParameters parameters, CancellationProbe cancellation,
        boolean directLongitudinalReliability, AttemptMemoryLedger.Owner owner) {
        if (profileChainage == null || owner == null) {
            throw new IllegalArgumentException("Measured profile chainage and memory owner are required");
        }
        List<ProbabilisticProfile> result = create(sourcePolyline,
                profileChainage.configuredStepMeters(), searchHalfWidthMeters, fixedEndpoints,
                evidence, field, parameters, cancellation, directLongitudinalReliability,
                profileChainage.sourceOriginGroundMeters(), owner);
        if (result.size() != profileChainage.cumulativeGroundMeters().size()) {
            throw new IllegalArgumentException("Request chainage does not match deterministic profile sampling");
        }
        for (int index = 0; index < result.size(); index++) {
            if (Math.abs(result.get(index).chainageMeters()
                    - profileChainage.cumulativeGroundMeters().get(index)) > 1e-8) {
                throw new IllegalArgumentException(
                        "Request chainage differs from sampled profile anchors");
            }
        }
        return result;
    }

    private static ProbabilisticProfile ownedProfile(AttemptMemoryLedger.Owner owner,
            int sampleCount, SupplierProfile allocation) {
        AttemptMemoryLedger.Reservation profile = null;
        AttemptMemoryLedger.Reservation samples = null;
        try {
            profile = owner.reserve(AttemptMemoryLedger.objectBytes(104));
            samples = owner.reserve(ProbabilisticInference.listBytes(sampleCount));
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            if (samples != null) samples.close();
            if (profile != null) profile.close();
            throw new ProbabilisticInference.MemoryLimit("sampled profiles", exception);
        }
        try {
            ProbabilisticProfile result = allocation.get();
            profile.adopt(result);
            samples.adopt(result.samples());
            return result;
        } catch (RuntimeException | Error exception) {
            profile.close();
            samples.close();
            throw exception;
        }
    }

    static void retainSharedProfileGraph(ProbabilisticProfile profile,
            AttemptMemoryLedger.Owner owner) {
        owner.retainIfAbsent(profile.anchor());
        owner.retainIfAbsent(profile.normalUnit());
        for (int index = 0; index < profile.samples().size(); index++) {
            owner.retainIfAbsent(profile.samples().get(index));
        }
        for (int index = 0; index < profile.modes().size(); index++) {
            ProbabilisticProfile.Mode mode = profile.modes().get(index);
            if (!mode.peakOffsetsMeters().isEmpty()) {
                owner.retainIfAbsent(mode.peakOffsetsMeters());
            }
            if (!mode.nestedCenterOffsetsMeters().isEmpty()) {
                owner.retainIfAbsent(mode.nestedCenterOffsetsMeters());
            }
            retainOrientationGraphIfAbsent(owner, mode.orientationSupport());
        }
        if (!profile.censoredModes().isEmpty()) {
            owner.retainIfAbsent(profile.censoredModes());
            for (int index = 0; index < profile.censoredModes().size(); index++) {
                owner.retainIfAbsent(profile.censoredModes().get(index));
            }
        }
        if (profile.exactAnchorOffsetMeters().isPresent()) {
            owner.retainIfAbsent(profile.exactAnchorOffsetMeters());
        }
        retainOrientationGraphIfAbsent(owner, profile.orientationSupport());
    }

    static ProbabilisticProfile ownedReliabilityProfile(ProbabilisticProfile profile,
            List<ProbabilisticProfile.Mode> modes, AttemptMemoryLedger.Owner owner) {
        return ownedProfile(owner, profile.samples().size(), () -> new ProbabilisticProfile(
                profile.profileIndex(), profile.chainageMeters(), profile.anchor(),
                profile.normalUnit(), profile.minimumOffsetMeters(), profile.maximumOffsetMeters(),
                profile.sourcePitchMeters(), profile.nativePitchKnown(), profile.noiseFloor(),
                profile.samples(), modes, profile.censoredModes(),
                profile.exactAnchorOffsetMeters(), profile.orientationSupport()));
    }

    @FunctionalInterface
    private interface SupplierProfile {
        ProbabilisticProfile get();
    }

    @FunctionalInterface
    private interface SupplierMode {
        ProbabilisticProfile.Mode get();
    }

    @FunctionalInterface
    private interface SupplierSample {
        ProbabilisticProfile.Sample get();
    }

    @FunctionalInterface
    private interface SupplierCensored {
        ProbabilisticProfile.CensoredMode get();
    }

    @FunctionalInterface
    private interface SupplierPoint {
        MetricPoint get();
    }

    private static List<ProbabilisticProfile.Mode> bindOrientationToModes(
        List<ProbabilisticProfile.Mode> modes, MetricPoint anchor, MetricPoint normal,
        double sourcePitch, EvidenceSnapshot evidence, ScalarEvidenceField field,
        EvidenceModelParameters parameters, CancellationProbe cancellation,
        ImageOrientationDescriptor descriptor, boolean directLongitudinalReliability) {
        return bindOrientationToModes(modes, anchor, normal, sourcePitch, evidence, field,
                parameters, cancellation, descriptor, directLongitudinalReliability, null, null);
    }

    private static List<ProbabilisticProfile.Mode> bindOrientationToModes(
        List<ProbabilisticProfile.Mode> modes, MetricPoint anchor, MetricPoint normal,
        double sourcePitch, EvidenceSnapshot evidence, ScalarEvidenceField field,
        EvidenceModelParameters parameters, CancellationProbe cancellation,
        ImageOrientationDescriptor descriptor, boolean directLongitudinalReliability,
        AttemptMemoryLedger.Owner owner, AttemptMemoryLedger.Owner retainedOwner) {
        AttemptMemoryLedger.Owner builder = owner == null ? null : owner.child("oriented-modes");
        long bytes = ProbabilisticInference.listBytes(modes.size());
        List<ProbabilisticProfile.Mode> result = owner == null ? new ArrayList<>(modes.size())
                : ProbabilisticInference.allocated(builder, bytes, "sampled profiles",
                    () -> new ArrayList<>(modes.size()));
        for (ProbabilisticProfile.Mode mode : modes) {
            cancellation.checkpoint();
            MetricPoint center = offset(anchor, normal, mode.coreCenterMeters());
            ImageOrientationSupport support = descriptor.describe(evidence, field, center,
                sourcePitch, parameters, cancellation, owner).support();
            if (retainedOwner != null) {
                retainedOwner.retain(mode.peakOffsetsMeters());
                retainedOwner.retain(mode.nestedCenterOffsetsMeters());
                retainOrientationGraph(retainedOwner, support);
            }
            double corroboration = directLongitudinalReliability
                    ? mode.localizationConfidence()
                    : mode.localizationConfidence() * support.certainty();
            double reliability = directLongitudinalReliability
                    ? ProbabilisticProfile.Mode.combineReliability(
                            mode.scalarAmplitudeReliability(), corroboration)
                    : mode.scalarAmplitudeReliability()
                            + (1.0 - mode.scalarAmplitudeReliability()) * corroboration;
            SupplierMode allocation = () -> new ProbabilisticProfile.Mode(mode.id(),
                    mode.evidenceLineage(), mode.coreMinimumMeters(), mode.coreMaximumMeters(),
                    mode.localizationSigmaMeters(), mode.existenceConfidence(),
                    mode.localizationConfidence(), mode.peakOffsetsMeters(),
                    mode.nestedCenterOffsetsMeters(), mode.groupedParent(), support,
                    mode.scalarAmplitudeReliability(), corroboration, reliability);
            result.add(retainedOwner == null ? allocation.get()
                    : ProbabilisticInference.allocated(retainedOwner,
                        AttemptMemoryLedger.objectBytes(112), "sampled profiles", allocation::get));
        }
        if (owner == null) return List.copyOf(result);
        try {
            if (result.isEmpty()) return List.of();
            return ProbabilisticInference.allocated(retainedOwner,
                    ProbabilisticInference.listBytes(result.size()), "sampled profiles",
                    () -> List.copyOf(result));
        } finally {
            builder.close();
        }
    }

    private static ImageOrientationSupport aggregateOrientation(
        List<ProbabilisticProfile.Mode> modes, AttemptMemoryLedger.Owner owner) {
        if (modes.stream().anyMatch(mode -> mode.orientationSupport().status()
            == ImageOrientationSupport.Status.RESOURCE_LIMIT)) {
            return ownedOrientationUnknown(ImageOrientationSupport.Status.RESOURCE_LIMIT, owner);
        }
        AttemptMemoryLedger.Owner builder = owner == null ? null
                : owner.child("aggregate-orientation-builder");
        try {
            return aggregateOrientation(modes, owner, builder);
        } finally {
            if (builder != null) builder.close();
        }
    }

    private static ImageOrientationSupport aggregateOrientation(
            List<ProbabilisticProfile.Mode> modes, AttemptMemoryLedger.Owner owner,
            AttemptMemoryLedger.Owner builder) {
        List<ImageOrientationSupport> measured = builder == null
                ? new ArrayList<>(modes.size())
                : ProbabilisticInference.allocated(builder,
                    ProbabilisticInference.listBytes(modes.size()), "sampled profiles",
                    () -> new ArrayList<>(modes.size()));
        for (ProbabilisticProfile.Mode mode : modes) {
            if (!mode.orientationSupport().modes().isEmpty()) {
                measured.add(mode.orientationSupport());
            }
        }
        if (measured.isEmpty()) {
            ImageOrientationSupport.Status status = modes.stream()
                .map(ProbabilisticProfile.Mode::orientationSupport)
                .map(ImageOrientationSupport::status)
                .filter(candidate -> candidate == ImageOrientationSupport.Status.INVALID_CENTER)
                .findFirst().orElse(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT);
            return ownedOrientationUnknown(status, owner);
        }
        int angularModeCount = 0;
        for (ImageOrientationSupport support : measured) {
            angularModeCount = Math.addExact(angularModeCount, support.modes().size());
        }
        int retainedAngularModeCount = angularModeCount;
        List<ImageOrientationSupport.AngularMode> angularModes = builder == null
                ? new ArrayList<>(angularModeCount)
                : ProbabilisticInference.allocated(builder,
                    ProbabilisticInference.listBytes(angularModeCount), "sampled profiles",
                    () -> new ArrayList<>(retainedAngularModeCount));
        for (ImageOrientationSupport support : measured) angularModes.addAll(support.modes());
        AttemptMemoryLedger.Reservation sortingScratch = builder == null ? null
                : reserve(builder, AttemptMemoryLedger.referenceArrayBytes(angularModeCount));
        try {
            angularModes.sort(java.util.Comparator.comparingDouble(
                    ImageOrientationSupport.AngularMode::peakBearingRadians));
        } finally {
            if (sortingScratch != null) sortingScratch.close();
        }
        double certainty = measured.stream().mapToDouble(ImageOrientationSupport::certainty)
            .max().orElse(0.0);
        if (owner == null) return new ImageOrientationSupport(
                ImageOrientationSupport.Status.MEASURED_TWO_SIDED, angularModes, certainty);
        List<ImageOrientationSupport.AngularMode> retained = ProbabilisticInference.allocated(owner,
                ProbabilisticInference.listBytes(angularModes.size()), "sampled profiles",
                () -> List.copyOf(angularModes));
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(24),
                "sampled profiles", () -> new ImageOrientationSupport(
                    ImageOrientationSupport.Status.MEASURED_TWO_SIDED, retained, certainty));
    }

    private static AttemptMemoryLedger.Reservation reserve(AttemptMemoryLedger.Owner owner,
            long bytes) {
        try {
            return owner.reserve(bytes);
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            throw new ProbabilisticInference.MemoryLimit("sampled profiles", exception);
        }
    }

    private static ImageOrientationSupport ownedOrientationUnknown(
            ImageOrientationSupport.Status status, AttemptMemoryLedger.Owner owner) {
        if (owner == null) return ImageOrientationSupport.unknown(status);
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(24),
                "sampled profiles", () -> ImageOrientationSupport.unknown(status));
    }

    private static void retainOrientationGraph(AttemptMemoryLedger.Owner owner,
            ImageOrientationSupport support) {
        owner.retain(support);
        if (!support.modes().isEmpty()) {
            owner.retain(support.modes());
            for (ImageOrientationSupport.AngularMode mode : support.modes()) owner.retain(mode);
        }
    }

    private static void retainOrientationGraphIfAbsent(AttemptMemoryLedger.Owner owner,
            ImageOrientationSupport support) {
        owner.retainIfAbsent(support);
        if (!support.modes().isEmpty()) {
            owner.retainIfAbsent(support.modes());
            for (int index = 0; index < support.modes().size(); index++) {
                owner.retainIfAbsent(support.modes().get(index));
            }
        }
    }

    /** Samples one scalar value using strict bilinear validity. */
    public OptionalDouble sample(EvidenceSnapshot evidence, ScalarEvidenceField field,
        MetricPoint point) {
        return StrictScalarSampler.sample(field, evidence.transform(), evidence.evidenceRegion(), point);
    }

    private List<ProbabilisticProfile.Sample> sampleProfile(MetricPoint anchor, MetricPoint normal,
        double minimum, double maximum, double pitch, EvidenceSnapshot evidence,
        ScalarEvidenceField field, AttemptMemoryLedger.Owner owner,
        AttemptMemoryLedger.Owner retainedOwner) {
        int intervals = Math.max(1, (int) Math.ceil((maximum - minimum) / pitch));
        int count = intervals + 1;
        AttemptMemoryLedger.Owner builder = owner == null ? null : owner.child("scalar-samples");
        long bytes = ProbabilisticInference.listBytes(count);
        List<ProbabilisticProfile.Sample> result = owner == null ? new ArrayList<>(count)
                : ProbabilisticInference.allocated(builder, bytes, "sampled profiles",
                    () -> new ArrayList<>(count));
        for (int index = 0; index <= intervals; index++) {
            double offset = index == intervals ? maximum : minimum + index * (maximum - minimum) / intervals;
            AttemptMemoryLedger.Owner pointOwner = owner == null ? null : owner.child("scalar-point");
            MetricPoint point = pointOwner == null ? offset(anchor, normal, offset)
                    : ProbabilisticInference.allocated(pointOwner,
                        AttemptMemoryLedger.objectBytes(16), "sampled profiles",
                        () -> offset(anchor, normal, offset));
            OptionalDouble value;
            try {
                value = sample(evidence, field, point);
            } finally {
                if (pointOwner != null) pointOwner.close();
            }
            SupplierSample allocation = () -> new ProbabilisticProfile.Sample(offset,
                    value.orElse(Double.NaN), value.isPresent());
            result.add(retainedOwner == null ? allocation.get()
                    : ProbabilisticInference.allocated(retainedOwner,
                        AttemptMemoryLedger.objectBytes(24), "sampled profiles", allocation::get));
        }
        if (owner == null) return List.copyOf(result);
        try {
            return ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(result.size()), "sampled profiles",
                    () -> List.copyOf(result));
        } finally {
            builder.close();
        }
    }

    private static ExtractedModes adaptModes(LocalScalarProfileExtractor.Result extracted,
            int profileIndex, AttemptMemoryLedger.Owner owner,
            AttemptMemoryLedger.Owner retainedOwner) {
        int modeCount = extracted.modes().size();
        int censoredCount = extracted.censoredModes().size();
        AttemptMemoryLedger.Owner builder = owner == null ? null : owner.child("adapted-modes");
        List<ProbabilisticProfile.Mode> modes = owner == null ? new ArrayList<>()
                : ProbabilisticInference.allocated(builder,
                    ProbabilisticInference.listBytes(modeCount)
                        + Math.multiplyExact(modeCount, AttemptMemoryLedger.objectBytes(112)),
                    "sampled profiles", () -> new ArrayList<>(modeCount));
        List<ProbabilisticProfile.CensoredMode> censored = owner == null ? new ArrayList<>()
                : ProbabilisticInference.allocated(builder,
                    ProbabilisticInference.listBytes(censoredCount),
                    "sampled profiles", () -> new ArrayList<>(censoredCount));
        int modeIndex = 0;
        for (LocalScalarProfileExtractor.Mode mode : extracted.modes()) {
            String id = "p" + profileIndex + "-m" + modeIndex++;
            String lineage = id + "-scalar-band";
            modes.add(new ProbabilisticProfile.Mode(id, lineage, mode.coreMinimumMeters(),
                    mode.coreMaximumMeters(), mode.localizationSigmaMeters(),
                    mode.existenceConfidence(), mode.localizationConfidence(), mode.peakOffsetsMeters(),
                    mode.nestedCenterOffsetsMeters(), false,
                    ImageOrientationSupport.unknown(
                        ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),
                    mode.scalarAmplitudeReliability(), 0.0, mode.scalarAmplitudeReliability()));
        }
        for (LocalScalarProfileExtractor.CensoredMode mode : extracted.censoredModes()) {
            String id = "p" + profileIndex + "-m" + modeIndex++;
            String lineage = id + "-scalar-band";
            ProbabilisticProfile.CensorSide side = mode.side()
                    == LocalScalarProfileExtractor.CensorSide.RIGHT
                    ? ProbabilisticProfile.CensorSide.RIGHT : ProbabilisticProfile.CensorSide.LEFT;
            SupplierCensored allocation = () -> new ProbabilisticProfile.CensoredMode(id, lineage,
                    side, mode.boundaryOffsetMeters(), mode.existenceConfidence(),
                    mode.gradientTowardEdge());
            censored.add(retainedOwner == null ? allocation.get()
                    : ProbabilisticInference.allocated(retainedOwner,
                        AttemptMemoryLedger.objectBytes(40), "sampled profiles", allocation::get));
        }
        if (owner == null) return new ExtractedModes(List.copyOf(modes), List.copyOf(censored));
        try {
            // List.of() is a borrowed JVM singleton when empty, so only newly allocated
            // nonempty immutable copies belong to this attempt.
            List<ProbabilisticProfile.Mode> retainedModes = modes.isEmpty() ? List.of()
                    : ProbabilisticInference.allocated(owner,
                        ProbabilisticInference.listBytes(modes.size()), "sampled profiles",
                        () -> List.copyOf(modes));
            List<ProbabilisticProfile.CensoredMode> retainedCensored =
                    censored.isEmpty() ? List.of()
                    : ProbabilisticInference.allocated(retainedOwner,
                        ProbabilisticInference.listBytes(censored.size()), "sampled profiles",
                        () -> List.copyOf(censored));
            return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(16),
                    "sampled profiles",
                    () -> new ExtractedModes(retainedModes, retainedCensored));
        } finally {
            builder.close();
        }
    }

    private static double authorizedBoundary(MetricPoint anchor, MetricPoint normal, double direction,
        double maximum, double step, EvidenceSnapshot evidence) {
        double last = 0.0;
        for (double distance = Math.min(step, maximum); distance <= maximum + 1e-9;
             distance += step) {
            double bounded = Math.min(distance, maximum);
            if (!evidence.routePositionAuthorized(offset(anchor, normal, direction * bounded))) {
                break;
            }
            last = bounded;
            if (bounded == maximum) {
                break;
            }
        }
        return direction * last;
    }

    private static ResampledCurve resample(List<MetricPoint> points, double step,
            AttemptMemoryLedger.Owner owner) {
        long sourceBytes = ProbabilisticInference.listBytes(points.size())
                + Math.multiplyExact(points.size(), AttemptMemoryLedger.objectBytes(8));
        List<Double> sourceChainage = owner == null ? new ArrayList<>(points.size())
                : ProbabilisticInference.allocated(owner, sourceBytes, "sampled profiles",
                    () -> new ArrayList<>(points.size()));
        sourceChainage.add(0.0);
        for (int index = 1; index < points.size(); index++) {
            sourceChainage.add(sourceChainage.get(index - 1) + points.get(index - 1).distanceTo(points.get(index)));
        }
        double length = sourceChainage.get(sourceChainage.size() - 1);
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Source polyline has zero length");
        }
        int intervals = Math.max(1, (int) Math.ceil(length / step));
        int sampledCount = intervals + 1;
        List<MetricPoint> sampled = owner == null ? new ArrayList<>(sampledCount)
                : ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(sampledCount),
                    "sampled profiles", () -> new ArrayList<>(sampledCount));
        List<Double> chainage = owner == null ? new ArrayList<>(sampledCount)
                : ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(sampledCount)
                        + Math.multiplyExact(sampledCount, AttemptMemoryLedger.objectBytes(8)),
                    "sampled profiles", () -> new ArrayList<>(sampledCount));
        int segment = 1;
        for (int index = 0; index <= intervals; index++) {
            double target = index == intervals ? length : index * length / intervals;
            while (segment < sourceChainage.size() - 1 && sourceChainage.get(segment) < target) {
                segment++;
            }
            double startDistance = sourceChainage.get(segment - 1);
            double endDistance = sourceChainage.get(segment);
            double fraction = (target - startDistance) / (endDistance - startDistance);
            MetricPoint start = points.get(segment - 1);
            MetricPoint end = points.get(segment);
            SupplierPoint allocation = () -> new MetricPoint(
                    start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                    start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
            sampled.add(owner == null ? allocation.get()
                    : ProbabilisticInference.allocated(owner,
                        AttemptMemoryLedger.objectBytes(16), "sampled profiles", allocation::get));
            chainage.add(target);
        }
        if (owner == null) return new ResampledCurve(List.copyOf(sampled), List.copyOf(chainage));
        List<MetricPoint> retainedSampled = ProbabilisticInference.allocated(owner,
                ProbabilisticInference.listBytes(sampled.size()), "sampled profiles",
                () -> List.copyOf(sampled));
        List<Double> retainedChainage = ProbabilisticInference.allocated(owner,
                ProbabilisticInference.listBytes(chainage.size()), "sampled profiles",
                () -> List.copyOf(chainage));
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(16),
                "sampled profiles", () -> new ResampledCurve(retainedSampled, retainedChainage));
    }

    private static MetricPoint tangent(List<MetricPoint> points, int index) {
        MetricPoint start = points.get(Math.max(0, index - 1));
        MetricPoint end = points.get(Math.min(points.size() - 1, index + 1));
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double length = StrictMath.hypot(dx, dy);
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Resampled source has a zero tangent");
        }
        return new MetricPoint(dx / length, dy / length);
    }

    private static MetricPoint offset(MetricPoint anchor, MetricPoint normal, double offset) {
        return new MetricPoint(anchor.xMeters() + normal.xMeters() * offset,
            anchor.yMeters() + normal.yMeters() * offset);
    }

    /**
     * Visits every interpolation cell used anywhere along a raster segment.
     *
     * <p>Integer grid events and every open interval between them are checked. This catches cells
     * crossed for less than the regular prominence-sampling step and uses the field's deterministic
     * floor-cell ownership at grid boundaries and corners.</p>
     */
    static boolean supportsEveryCrossedInterpolationCell(ScalarEvidenceField field,
            RasterMetricTransform transform, MetricPoint start, MetricPoint end,
            CancellationProbe cancellation, LongConsumer admission) {
        return supportsEveryCrossedInterpolationCell(field, transform, start, end,
                cancellation, admission, null);
    }

    private static boolean supportsEveryCrossedInterpolationCell(ScalarEvidenceField field,
            RasterMetricTransform transform, MetricPoint start, MetricPoint end,
            CancellationProbe cancellation, LongConsumer admission,
            AttemptMemoryLedger.Owner owner) {
        if (field == null || transform == null || start == null || end == null
                || cancellation == null || admission == null) {
            throw new IllegalArgumentException("Raster interval traversal inputs are incomplete");
        }
        RasterPoint rasterStart = transform.metricToPixelCenter(start);
        RasterPoint rasterEnd = transform.metricToPixelCenter(end);
        if (!checkInterpolationPoint(field, rasterStart.x(), rasterStart.y(), cancellation)
                || !checkInterpolationPoint(field, rasterEnd.x(), rasterEnd.y(), cancellation)) {
            return false;
        }
        long xCrossings = gridCrossingCount(rasterStart.x(), rasterEnd.x());
        long yCrossings = gridCrossingCount(rasterStart.y(), rasterEnd.y());
        long eventCount = Math.addExact(2L, Math.addExact(xCrossings, yCrossings));
        long conservativeChecks = Math.multiplyExact(2L, eventCount);
        admission.accept(conservativeChecks);
        if (eventCount > Integer.MAX_VALUE) {
            throw new LongitudinalModeReliability.ResourceLimitException();
        }

        AttemptMemoryLedger.Owner eventOwner = owner == null ? null
                : owner.child("raster-crossing-events");
        List<Double> events = owner == null ? new ArrayList<>((int) eventCount)
                : ProbabilisticInference.allocated(eventOwner,
                    ProbabilisticInference.listBytes(eventCount)
                        + Math.multiplyExact(eventCount, AttemptMemoryLedger.objectBytes(8)),
                    "sampled profiles", () -> new ArrayList<>((int) eventCount));
        try {
            events.add(0.0);
            events.add(1.0);
            addGridCrossings(rasterStart.x(), rasterEnd.x(), events, cancellation);
            addGridCrossings(rasterStart.y(), rasterEnd.y(), events, cancellation);
            events.sort(Double::compare);

            double previous = events.get(0);
            for (int index = 1; index < events.size(); index++) {
                double current = events.get(index);
                if (current > previous) {
                    double midpoint = 0.5 * (previous + current);
                    if (!checkInterpolationPoint(field,
                            rasterStart.x() + midpoint * (rasterEnd.x() - rasterStart.x()),
                            rasterStart.y() + midpoint * (rasterEnd.y() - rasterStart.y()),
                            cancellation)) {
                        return false;
                    }
                }
                if (Double.compare(current, previous) != 0
                        && !checkInterpolationPoint(field,
                                rasterStart.x() + current * (rasterEnd.x() - rasterStart.x()),
                                rasterStart.y() + current * (rasterEnd.y() - rasterStart.y()),
                                cancellation)) {
                    return false;
                }
                previous = current;
            }
            return true;
        } finally {
            if (eventOwner != null) eventOwner.close();
        }
    }

    private static boolean checkInterpolationPoint(ScalarEvidenceField field, double x, double y,
            CancellationProbe cancellation) {
        cancellation.checkpoint();
        return field.supportsInterpolationAt(x, y);
    }

    private static long gridCrossingCount(double start, double end) {
        if (Double.doubleToLongBits(start) == Double.doubleToLongBits(end)) {
            return 0L;
        }
        double minimum = Math.min(start, end);
        double maximum = Math.max(start, end);
        int first = (int) Math.floor(minimum) + 1;
        int last = (int) Math.ceil(maximum) - 1;
        return Math.max(0L, (long) last - first + 1L);
    }

    private static void addGridCrossings(double start, double end, List<Double> events,
            CancellationProbe cancellation) {
        if (Double.doubleToLongBits(start) == Double.doubleToLongBits(end)) {
            return;
        }
        double minimum = Math.min(start, end);
        double maximum = Math.max(start, end);
        int first = (int) Math.floor(minimum) + 1;
        int last = (int) Math.ceil(maximum) - 1;
        for (int boundary = first; boundary <= last; boundary++) {
            cancellation.checkpoint();
            double fraction = (boundary - start) / (end - start);
            if (fraction > 0.0 && fraction < 1.0) {
                events.add(fraction);
            }
        }
    }

    /**
     * Proves that adjacent localized centers are joined by directly measured scalar evidence.
     *
     * <p>Validity and the exact decision-region union are hard ownership boundaries. Inside those
     * boundaries the minimum prominence ratio remains continuous, so a faint but concentrated
     * corridor can retain full coherence while a blank interval receives none.</p>
     */
    private static final class RasterIntervalEvidence
            implements LongitudinalModeReliability.DirectIntervalEvidence {
        private static final long MAXIMUM_SAMPLES = 2_000_000L;
        private static final double EPSILON = 1e-12;

        private final EvidenceSnapshot evidence;
        private final ScalarEvidenceField field;
        private final AttemptMemoryLedger.Owner owner;
        private long samples;

        RasterIntervalEvidence(EvidenceSnapshot evidence, ScalarEvidenceField field,
                AttemptMemoryLedger.Owner owner) {
            this.evidence = evidence;
            this.field = field;
            this.owner = owner;
        }

        @Override
        public double support(ProbabilisticProfile leftProfile,
                ProbabilisticProfile.Mode leftMode, ProbabilisticProfile rightProfile,
                ProbabilisticProfile.Mode rightMode, CancellationProbe cancellation) {
            MetricPoint left = modeCenter(leftProfile, leftMode);
            MetricPoint right = modeCenter(rightProfile, rightMode);
            if (!evidence.routeSegmentAuthorized(left, right)) {
                return 0.0;
            }
            double distance = left.distanceTo(right);
            if (!(distance > 0.0)) {
                return 0.0;
            }
            double leftProminence = directProminence(leftProfile, leftMode);
            double rightProminence = directProminence(rightProfile, rightMode);
            double referenceProminence = Math.min(leftProminence, rightProminence);
            if (!(referenceProminence > EPSILON)) {
                return 0.0;
            }
            if (!supportsEveryCrossedInterpolationCell(field, evidence.transform(), left, right,
                    cancellation, this::charge, owner)) {
                return 0.0;
            }
            double samplingStep = 0.5 * evidence.resolution().outputRasterPitchMeters();
            long intervals = Math.max(1L, (long) Math.ceil(distance / samplingStep));
            charge(intervals + 1L);
            double minimumRatio = 1.0;
            for (long index = 0L; index <= intervals; index++) {
                cancellation.checkpoint();
                double fraction = (double) index / intervals;
                MetricPoint point = new MetricPoint(
                        left.xMeters() + fraction * (right.xMeters() - left.xMeters()),
                        left.yMeters() + fraction * (right.yMeters() - left.yMeters()));
                OptionalDouble sampled = StrictScalarSampler.sample(
                        field, evidence.transform(), evidence.evidenceRegion(), point);
                if (sampled.isEmpty()) {
                    return 0.0;
                }
                double noiseFloor = leftProfile.noiseFloor()
                        + fraction * (rightProfile.noiseFloor() - leftProfile.noiseFloor());
                double ratio = (sampled.getAsDouble() - noiseFloor) / referenceProminence;
                if (!(ratio > 0.0)) {
                    return 0.0;
                }
                minimumRatio = Math.min(minimumRatio, ratio);
            }
            return Math.min(1.0, minimumRatio);
        }

        private void charge(long requested) {
            if (requested < 0L || requested > MAXIMUM_SAMPLES - samples) {
                throw new LongitudinalModeReliability.ResourceLimitException();
            }
            samples += requested;
        }

        private static MetricPoint modeCenter(ProbabilisticProfile profile,
                ProbabilisticProfile.Mode mode) {
            double offset = mode.coreCenterMeters();
            return new MetricPoint(
                    profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset,
                    profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset);
        }

        private static double directProminence(ProbabilisticProfile profile,
                ProbabilisticProfile.Mode mode) {
            double tolerance = 1e-9 * Math.max(1.0, profile.sourcePitchMeters());
            double maximum = 0.0;
            for (ProbabilisticProfile.Sample sample : profile.samples()) {
                if (sample.valid()
                        && sample.offsetMeters() + tolerance >= mode.coreMinimumMeters()
                        && sample.offsetMeters() - tolerance <= mode.coreMaximumMeters()) {
                    maximum = Math.max(maximum, sample.intensity() - profile.noiseFloor());
                }
            }
            return maximum;
        }
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private record ResampledCurve(List<MetricPoint> points, List<Double> chainageMeters) { }
    private record ExtractedModes(List<ProbabilisticProfile.Mode> modes,
        List<ProbabilisticProfile.CensoredMode> censoredModes) { }
}
