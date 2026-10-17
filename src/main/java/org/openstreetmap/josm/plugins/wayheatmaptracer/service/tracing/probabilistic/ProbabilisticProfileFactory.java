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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ProbabilisticRawCoreContextVeto;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.StrictScalarSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Samples full scalar cross-sections and extracts deterministic finite observation hypotheses. */
public final class ProbabilisticProfileFactory {
    /** Exact source-only geometry from the same resampling arithmetic used by native B. */
    public record SourceSamplingGeometry(List<MetricPoint> points, List<Double> chainageMeters) {
        public SourceSamplingGeometry {
            points = List.copyOf(points);
            chainageMeters = List.copyOf(chainageMeters);
        }
    }

    /** Reconstructs native sampling anchors without reading or interpreting image evidence. */
    public SourceSamplingGeometry sourceSamplingGeometry(List<MetricPoint> sourcePolyline,
            double configuredStepMeters) {
        if (sourcePolyline == null || sourcePolyline.size() < 2 || !positive(configuredStepMeters)) {
            throw new IllegalArgumentException("Source sampling inputs are incomplete");
        }
        ResampledCurve curve = resample(sourcePolyline, configuredStepMeters, null);
        return new SourceSamplingGeometry(curve.points(), curve.chainageMeters());
    }

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
        AttemptMemoryLedger.Owner intervalOwner = !directLongitudinalReliability || owner == null ? null
                : owner.child("reliability-raster-intervals");
        AttemptMemoryLedger.Owner detailOwner = intervalOwner == null ? null
                : intervalOwner.child("original-profile-details");
        AttemptMemoryLedger.Owner identityOwner = intervalOwner == null ? null
                : intervalOwner.child("accepted-rms-identities");
        RasterIntervalEvidence interval = null;
        try {
        if (directLongitudinalReliability) {
            interval = intervalOwner == null
                    ? new RasterIntervalEvidence(evidence, field, parameters.localization(), null)
                    : ProbabilisticInference.allocated(intervalOwner, AttemptMemoryLedger.objectBytes(80),
                            "sampled profiles", () -> new RasterIntervalEvidence(
                                evidence, field, parameters.localization(), intervalOwner));
        }
        RasterIntervalEvidence sharedInterval = interval;
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
        List<LocalScalarProfileExtractor.DetailedResult> details = !directLongitudinalReliability ? null
                : detailOwner == null ? new ArrayList<>(curve.points().size())
                : ProbabilisticInference.allocated(detailOwner,
                    ProbabilisticInference.listBytes(curve.points().size()), "sampled profiles",
                    () -> new ArrayList<>(curve.points().size()));
        BoundaryWork boundaryWork = samplingOwner == null ? new BoundaryWork(cancellation)
                : ProbabilisticInference.allocated(samplingOwner, AttemptMemoryLedger.objectBytes(24),
                    "sampled profiles", () -> new BoundaryWork(cancellation));
        try {
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
                samplePitch, evidence, boundaryWork, profileTemporary);
            double maximum = authorizedBoundary(anchor, normal, 1.0, searchHalfWidthMeters,
                samplePitch, evidence, boundaryWork, profileTemporary);
            if (maximum - minimum < 1e-9) {
                throw new ProfileWindowUnavailableException(index);
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
            LocalScalarProfileExtractor.Result scalarFeatures;
            if (directLongitudinalReliability) {
                var detailed = profileExtractor.extractDetailed(extractorSamples, sourcePitch,
                        parameters.localization(), detailOwner, cancellation, sharedInterval::chargeFeatures);
                details.add(detailed);
                scalarFeatures = detailed.baseline();
            } else {
                scalarFeatures = profileExtractor.extract(extractorSamples, sourcePitch,
                        parameters.localization(), profileTemporary);
            }
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
        } finally {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                    "profile.boundaryGeometryUnits", boundaryWork.units);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                    "profile.boundaryGeometryLimit", BoundaryWork.MAXIMUM_UNITS);
        }
        if (samplingOwner != null) samplingOwner.close();
        if (directLongitudinalReliability) {
            AttemptMemoryLedger.Owner reliabilityOwner = owner == null ? null
                    : owner.child("profile-reliability-result-transaction");
            try {
            List<ProbabilisticProfile> frozen = immutableCopy(result, intervalOwner);
            List<LocalScalarProfileExtractor.DetailedResult> frozenDetails = immutableCopy(details, detailOwner);
            CorroboratedProfiles corroborated = restoreRmsSingletons(frozen, frozenDetails, evidence,
                    field, parameters, cancellation, orientationDescriptor, sharedInterval,
                    intervalOwner, rawOwner, identityOwner);
            List<ProbabilisticProfile> reliable = LongitudinalModeReliability.apply(corroborated.profiles(),
                    corroborated.evidence(), cancellation, reliabilityOwner).profiles();
            // Only accepted IDs survive the diagnostic lifetime; all copied mode/orientation
            // graphs were co-owned by ordinary reliability before raw inputs are released.
            if (identityOwner != null) identityOwner.transferTo(reliabilityOwner);
            boolean hasCensoredBand = false;
            for (int i = 0; i < reliable.size(); i++) {
                cancellation.checkpoint(); sharedInterval.chargeFeatures(1L);
                if (!reliable.get(i).censoredModes().isEmpty()) { hasCensoredBand = true; break; }
            }
            // No directional observation exists: retain the ordinary reliability graph
            // without duplicating every profile or changing the solver's memory peak.
            List<ProbabilisticProfile> completed = !hasCensoredBand ? reliable
                    : CensoredDirectionReliability.apply(reliable, sharedInterval::observedCensoredBand,
                            cancellation, sharedInterval, reliabilityOwner);
            // Publication is last: no cancellable or allocating work follows this transfer.
            if (reliabilityOwner != null) reliabilityOwner.transferTo(owner);
            return completed;
            } finally { if (reliabilityOwner != null) reliabilityOwner.close(); }
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
        } finally {
            if (interval != null) {
                org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                        "reliability.bridgeQueries", interval.queries);
                org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                        "reliability.intervalSamplingUnits", interval.samplingUnits);
                org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                        "reliability.bridgeScalarQueries", interval.scalarQueries);
                org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                        "reliability.bridgeFeatureUnits", interval.featureUnits);
                org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                        "reliability.intervalSamplingLimit", RasterIntervalEvidence.MAXIMUM_SAMPLING_UNITS);
                org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                        "reliability.bridgeFeatureLimit", RasterIntervalEvidence.MAXIMUM_FEATURE_UNITS);
            }
            if (intervalOwner != null) intervalOwner.close();
            if (samplingOwner != null) samplingOwner.close();
            if (rawOwner != null) rawOwner.close();
        }
    }

    private static <T> List<T> immutableCopy(List<T> values, AttemptMemoryLedger.Owner owner) {
        if (values.isEmpty()) return List.of();
        try (AttemptMemoryLedger.Reservation copyScratch = owner == null ? null
                : reserve(owner, AttemptMemoryLedger.referenceArrayBytes(values.size()))) {
            return owner == null ? List.copyOf(values) : ProbabilisticInference.allocated(owner,
                    ProbabilisticInference.listBytes(values.size()), "sampled profiles", () -> List.copyOf(values));
        }
    }

    /** Consumes frozen original eligibility once; promoted profiles are never proposal inputs. */
    private static CorroboratedProfiles restoreRmsSingletons(List<ProbabilisticProfile> originals,
            List<LocalScalarProfileExtractor.DetailedResult> details, EvidenceSnapshot evidence,
            ScalarEvidenceField field, EvidenceModelParameters parameters, CancellationProbe cancellation,
            ImageOrientationDescriptor orientationDescriptor, RasterIntervalEvidence interval, AttemptMemoryLedger.Owner workOwner,
            AttemptMemoryLedger.Owner rawOwner, AttemptMemoryLedger.Owner identityOwner) {
        var proposals = ProbabilisticRmsCorroborationProposals.build(originals, details,
                cancellation, workOwner, interval::chargeFeatures);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "reliability.rmsProposals", proposals.size());
        if (proposals.isEmpty()) return allocatedCorroborated(originals, interval, workOwner);
        AttemptMemoryLedger.Owner builder = workOwner == null ? null : workOwner.child("rms-restoration-builder");
        try {
            List<AcceptedRms> accepted = builder == null ? new ArrayList<>(proposals.size())
                    : ProbabilisticInference.allocated(builder, ProbabilisticInference.listBytes(proposals.size()),
                        "sampled profiles", () -> new ArrayList<>(proposals.size()));
            for (var proposal : proposals) {
                cancellation.checkpoint();
                interval.chargeFeatures(1);
                int index = exactProfileIndex(originals, proposal.profile(), cancellation, interval);
                if (index <= 0 || index + 1 >= originals.size()
                        || originals.get(index - 1) != proposal.leftProfile()
                        || originals.get(index + 1) != proposal.rightProfile()) {
                    throw new IllegalStateException("RMS proposal lost original adjacency");
                }
                boolean exactSuppression = false;
                for (var suppression : details.get(index).suppressedModes()) {
                    cancellation.checkpoint();
                    interval.chargeFeatures(1);
                    if (suppression == proposal.suppression()) exactSuppression = true;
                }
                if (!exactSuppression) throw new IllegalStateException("RMS proposal lost suppression identity");
                AttemptMemoryLedger.Owner candidateOwner = workOwner == null ? null
                        : workOwner.child("rms-candidate");
                AttemptMemoryLedger.Owner candidateIdentities = identityOwner == null ? null
                        : identityOwner.child("rms-candidate-identities");
                try {
                    var shape = proposal.suppression().mode();
                    String id = rmsId(proposal.profile().profileIndex(), proposal.suppression(), candidateIdentities);
                    String lineage = ownedString(candidateIdentities, id.length() + 12, () -> id + "-scalar-band");
                    try (AttemptMemoryLedger.Reservation centerScratch = candidateOwner == null ? null
                            : reserve(candidateOwner, AttemptMemoryLedger.objectBytes(16))) {
                        MetricPoint center = offset(proposal.profile().anchor(), proposal.profile().normalUnit(),
                                shape.centerMeters());
                        // Admission uses exactly the descriptor's existing native-pitch ray lattice.
                        long orientationSamples = orientationSamplingUnits(proposal.profile().sourcePitchMeters(),
                                parameters.localization());
                        interval.chargeSampling(orientationSamples);
                        CancellationProbe orientationCancellation = () -> {
                            cancellation.checkpoint();
                            interval.chargeFeatures(1);
                            return false;
                        };
                        try (AttemptMemoryLedger.Reservation callbackScratch = candidateOwner == null ? null
                                : reserve(candidateOwner, AttemptMemoryLedger.objectBytes(16))) {
                            var measured = orientationDescriptor.describe(evidence, field, center,
                                    proposal.profile().sourcePitchMeters(), parameters,
                                    orientationCancellation, candidateOwner);
                            interval.scalarQueries += 1L + measured.sampledRayPoints();
                            if (measured.support().status() == ImageOrientationSupport.Status.RESOURCE_LIMIT) {
                                throw new LongitudinalModeReliability.ResourceLimitException();
                            }
                            if (measured.support().status() != ImageOrientationSupport.Status.MEASURED_TWO_SIDED) continue;
                            var support = measured.support();
                            ProbabilisticProfile.Mode mode = candidateOwner == null
                                    ? restoredMode(id, lineage, shape, support)
                                    : ProbabilisticInference.allocated(candidateOwner,
                                        AttemptMemoryLedger.objectBytes(128), "sampled profiles",
                                        () -> restoredMode(id, lineage, shape, support));
                            double leftFactor = interval.supportRmsSuppressedPair(proposal.leftProfile(),
                                    proposal.leftMode(), proposal.profile(), mode, proposal.profile().profileIndex(),
                                    proposal.suppression(), cancellation);
                            double rightFactor = interval.supportRmsSuppressedPair(proposal.profile(), mode,
                                    proposal.rightProfile(), proposal.rightMode(), proposal.profile().profileIndex(),
                                    proposal.suppression(), cancellation);
                            if (!(leftFactor > 0) || !(rightFactor > 0)) continue;
                            if (rawOwner != null) {
                                rawOwner.retain(mode);
                                if (!mode.peakOffsetsMeters().isEmpty()) rawOwner.retainIfAbsent(mode.peakOffsetsMeters());
                                if (!mode.nestedCenterOffsetsMeters().isEmpty()) rawOwner.retainIfAbsent(mode.nestedCenterOffsetsMeters());
                                retainOrientationGraphIfAbsent(rawOwner, support);
                                candidateIdentities.transferTo(identityOwner);
                            }
                            accepted.add(builder == null ? new AcceptedRms(index, proposal, mode, leftFactor, rightFactor)
                                    : ProbabilisticInference.allocated(builder, AttemptMemoryLedger.objectBytes(40),
                                        "sampled profiles", () -> new AcceptedRms(index, proposal, mode, leftFactor, rightFactor)));
                        }
                    }
                } finally {
                    if (candidateIdentities != null) candidateIdentities.close();
                    if (candidateOwner != null) candidateOwner.close();
                }
            }
            if (accepted.isEmpty()) return allocatedCorroborated(originals, interval, workOwner);
            List<ProbabilisticProfile> augmented = builder == null ? new ArrayList<>(originals)
                    : ProbabilisticInference.allocated(builder, ProbabilisticInference.listBytes(originals.size()),
                        "sampled profiles", () -> new ArrayList<>(originals));
            for (var item : accepted) {
                cancellation.checkpoint();
                interval.chargeFeatures(1);
                var original = originals.get(item.index());
                List<ProbabilisticProfile.Mode> modes = builder == null ? new ArrayList<>(original.modes().size() + 1)
                        : ProbabilisticInference.allocated(builder,
                            ProbabilisticInference.listBytes(original.modes().size() + 1), "sampled profiles",
                            () -> new ArrayList<>(original.modes().size() + 1));
                modes.addAll(original.modes());
                modes.add(item.mode());
                var frozenModes = immutableCopy(modes, rawOwner);
                var orientation = aggregateOrientation(frozenModes, rawOwner);
                SupplierProfile allocation = () -> new ProbabilisticProfile(original.profileIndex(),
                        original.chainageMeters(), original.anchor(), original.normalUnit(),
                        original.minimumOffsetMeters(), original.maximumOffsetMeters(), original.sourcePitchMeters(),
                        original.nativePitchKnown(), original.noiseFloor(), original.samples(), frozenModes,
                        original.censoredModes(), original.exactAnchorOffsetMeters(), orientation);
                augmented.set(item.index(), rawOwner == null ? allocation.get()
                        : ownedProfile(rawOwner, original.samples().size(), allocation));
            }
            var frozen = immutableCopy(augmented, workOwner);
            List<RmsLegReceipt> receipts = builder == null ? new ArrayList<>(2 * accepted.size())
                    : ProbabilisticInference.allocated(builder,
                        ProbabilisticInference.listBytes(2L * accepted.size()), "sampled profiles",
                        () -> new ArrayList<>(2 * accepted.size()));
            for (var item : accepted) {
                cancellation.checkpoint();
                interval.chargeFeatures(1);
                var proposal = item.proposal();
                var left = frozen.get(item.index() - 1);
                var candidate = frozen.get(item.index());
                var right = frozen.get(item.index() + 1);
                if (!sameOriginalObservation(proposal.leftProfile(), left, cancellation, interval)
                        || !sameOriginalObservation(proposal.profile(), candidate, cancellation, interval)
                        || !sameOriginalObservation(proposal.rightProfile(), right, cancellation, interval)
                        || !containsExactMode(left, proposal.leftMode(), cancellation, interval)
                        || !containsExactMode(candidate, item.mode(), cancellation, interval)
                        || !containsExactMode(right, proposal.rightMode(), cancellation, interval)) {
                    throw new IllegalStateException("RMS receipt lost measured endpoint provenance");
                }
                receipts.add(ownedRmsLeg(workOwner, left, proposal.leftMode(), candidate, item.mode(),
                        proposal.leftProfile(), proposal.profile(), proposal.suppression(), item.leftFactor()));
                receipts.add(ownedRmsLeg(workOwner, candidate, item.mode(), right, proposal.rightMode(),
                        proposal.profile(), proposal.rightProfile(), proposal.suppression(), item.rightFactor()));
            }
            var frozenReceipts = immutableCopy(receipts, workOwner);
            CertifiedRmsIntervalEvidence cached = workOwner == null
                    ? new CertifiedRmsIntervalEvidence(interval, frozenReceipts)
                    : ProbabilisticInference.allocated(workOwner, AttemptMemoryLedger.objectBytes(16),
                        "sampled profiles", () -> new CertifiedRmsIntervalEvidence(interval, frozenReceipts));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                    "reliability.rmsPromotions", accepted.size());
            return allocatedCorroborated(frozen, cached, workOwner);
        } finally {
            if (builder != null) builder.close();
        }
    }

    private static long orientationSamplingUnits(double pitch, EvidenceModelParameters.Localization localization) {
        double length = Math.max(localization.minimumOrientationRayMeters(), localization.orientationRayLengthPitches() * pitch);
        double intervals = Math.max(1, StrictMath.ceil((length - pitch) / (localization.maximumOrientationStepPitches() * pitch)));
        long rays = 2L * localization.orientationHeadingCount();
        if (!Double.isFinite(intervals) || intervals > Integer.MAX_VALUE - 1.0
                || intervals + 1 > localization.maximumOrientationSampleCount() / (double) rays) {
            throw new LongitudinalModeReliability.ResourceLimitException();
        }
        return Math.addExact(1, Math.multiplyExact(rays, (long) intervals + 1));
    }

    private static ProbabilisticProfile.Mode restoredMode(String id, String lineage,
            LocalScalarProfileExtractor.Mode shape, ImageOrientationSupport orientation) {
        return new ProbabilisticProfile.Mode(id, lineage, shape.coreMinimumMeters(), shape.coreMaximumMeters(),
                shape.localizationSigmaMeters(), shape.existenceConfidence(), shape.localizationConfidence(),
                shape.peakOffsetsMeters(), shape.nestedCenterOffsetsMeters(), false, orientation,
                shape.scalarAmplitudeReliability(), shape.localizationConfidence(),
                ProbabilisticProfile.Mode.combineReliability(shape.scalarAmplitudeReliability(), shape.localizationConfidence()));
    }

    private static String rmsId(int index, LocalScalarProfileExtractor.SuppressedMode suppression,
            AttemptMemoryLedger.Owner owner) {
        int length = 7 + decimalDigits(index) + decimalDigits(suppression.firstSampleIndex())
                + decimalDigits(suppression.lastSampleIndex());
        return ownedString(owner, length, () -> "p" + index + "-rms-" + suppression.firstSampleIndex()
                + "-" + suppression.lastSampleIndex());
    }

    private static int decimalDigits(int value) {
        int count = 1;
        while (value >= 10) { value /= 10; count++; }
        return count;
    }

    private static String ownedString(AttemptMemoryLedger.Owner owner, int length, java.util.function.Supplier<String> allocation) {
        return owner == null ? allocation.get() : ProbabilisticInference.allocated(owner,
                AttemptMemoryLedger.objectBytes(24) + AttemptMemoryLedger.arrayBytes(length, Character.BYTES),
                "sampled profiles", allocation);
    }

    private static int exactProfileIndex(List<ProbabilisticProfile> profiles, ProbabilisticProfile target,
            CancellationProbe cancellation, RasterIntervalEvidence interval) {
        for (int index = 0; index < profiles.size(); index++) {
            cancellation.checkpoint(); interval.chargeFeatures(1);
            if (profiles.get(index) == target) return index;
        }
        return -1;
    }

    private static boolean containsExactMode(ProbabilisticProfile profile, ProbabilisticProfile.Mode mode,
            CancellationProbe cancellation, RasterIntervalEvidence interval) {
        for (var candidate : profile.modes()) {
            cancellation.checkpoint(); interval.chargeFeatures(1);
            if (candidate == mode) return true;
        }
        return false;
    }

    private static boolean sameOriginalObservation(ProbabilisticProfile original, ProbabilisticProfile finalProfile,
            CancellationProbe cancellation, RasterIntervalEvidence interval) {
        if (!(original.profileIndex() == finalProfile.profileIndex()
                && original.chainageMeters() == finalProfile.chainageMeters()
                && original.anchor() == finalProfile.anchor() && original.normalUnit() == finalProfile.normalUnit()
                && original.samples().size() == finalProfile.samples().size()
                && original.censoredModes() == finalProfile.censoredModes()
                && original.exactAnchorOffsetMeters() == finalProfile.exactAnchorOffsetMeters()
                && original.minimumOffsetMeters() == finalProfile.minimumOffsetMeters()
                && original.maximumOffsetMeters() == finalProfile.maximumOffsetMeters()
                && original.sourcePitchMeters() == finalProfile.sourcePitchMeters()
                && original.nativePitchKnown() == finalProfile.nativePitchKnown()
                && original.noiseFloor() == finalProfile.noiseFloor())) return false;
        // The canonical profile constructor sorts into a new immutable list. Exact
        // row identities and order prove the borrowed observations across that copy.
        for (int index = 0; index < original.samples().size(); index++) {
            cancellation.checkpoint(); interval.chargeFeatures(1);
            if (original.samples().get(index) != finalProfile.samples().get(index)) return false;
        }
        return true;
    }

    private static CorroboratedProfiles allocatedCorroborated(List<ProbabilisticProfile> profiles,
            LongitudinalModeReliability.DirectIntervalEvidence evidence, AttemptMemoryLedger.Owner owner) {
        return owner == null ? new CorroboratedProfiles(profiles, evidence) : ProbabilisticInference.allocated(owner,
                AttemptMemoryLedger.objectBytes(16), "sampled profiles", () -> new CorroboratedProfiles(profiles, evidence));
    }

    private static RmsLegReceipt ownedRmsLeg(AttemptMemoryLedger.Owner owner, ProbabilisticProfile left,
            ProbabilisticProfile.Mode leftMode, ProbabilisticProfile right, ProbabilisticProfile.Mode rightMode,
            ProbabilisticProfile originalLeft, ProbabilisticProfile originalRight,
            LocalScalarProfileExtractor.SuppressedMode suppression, double factor) {
        return owner == null ? new RmsLegReceipt(left, leftMode, right, rightMode, originalLeft, originalRight, suppression, factor)
                : ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(64), "sampled profiles",
                    () -> new RmsLegReceipt(left, leftMode, right, rightMode, originalLeft, originalRight, suppression, factor));
    }

    private record CorroboratedProfiles(List<ProbabilisticProfile> profiles,
            LongitudinalModeReliability.DirectIntervalEvidence evidence) { }
    private record AcceptedRms(int index, ProbabilisticRmsCorroborationProposals.Proposal proposal,
            ProbabilisticProfile.Mode mode, double leftFactor, double rightFactor) { }

    /** Borrowed original provenance and exact final keys, alive only through reliability application. */
    record RmsLegReceipt(ProbabilisticProfile left, ProbabilisticProfile.Mode leftMode,
            ProbabilisticProfile right, ProbabilisticProfile.Mode rightMode,
            ProbabilisticProfile originalLeft, ProbabilisticProfile originalRight,
            LocalScalarProfileExtractor.SuppressedMode suppression, double factor) { }

    /** Ordinary mutual-best association controls whether an exact receipt is ever consumed. */
    static final class CertifiedRmsIntervalEvidence implements LongitudinalModeReliability.DirectIntervalEvidence {
        private final RasterIntervalEvidence ordinary;
        private final List<RmsLegReceipt> receipts;
        CertifiedRmsIntervalEvidence(RasterIntervalEvidence ordinary, List<RmsLegReceipt> receipts) {
            this.ordinary = ordinary; this.receipts = receipts;
        }
        @Override public double support(ProbabilisticProfile left, ProbabilisticProfile.Mode leftMode,
                ProbabilisticProfile right, ProbabilisticProfile.Mode rightMode, CancellationProbe cancellation) {
            RmsLegReceipt selected = null;
            for (var receipt : receipts) {
                cancellation.checkpoint(); ordinary.chargeFeatures(1);
                if (receipt.left() == left && receipt.leftMode() == leftMode
                        && receipt.right() == right && receipt.rightMode() == rightMode) {
                    if (selected != null) return 0;
                    selected = receipt;
                }
            }
            if (selected == null) return ordinary.support(left, leftMode, right, rightMode, cancellation);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add("reliability.rmsReceiptHits", 1);
            return selected.factor();
        }
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

    static ProbabilisticProfile ownedCensoredReliabilityProfile(ProbabilisticProfile profile,
            List<ProbabilisticProfile.CensoredMode> censored, AttemptMemoryLedger.Owner owner) {
        return ownedProfile(owner, profile.samples().size(), () -> new ProbabilisticProfile(
                profile.profileIndex(),profile.chainageMeters(),profile.anchor(),profile.normalUnit(),
                profile.minimumOffsetMeters(),profile.maximumOffsetMeters(),profile.sourcePitchMeters(),
                profile.nativePitchKnown(),profile.noiseFloor(),profile.samples(),profile.modes(),censored,
                profile.exactAnchorOffsetMeters(),profile.orientationSupport()));
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
                        AttemptMemoryLedger.objectBytes(128), "sampled profiles", allocation::get));
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
        // A symmetric authorized window must keep the source center as a sample.
        // Completing a residual edge must not flip grid parity and shift a
        // centered ridge by half a cell. Keep the grid uniform and its gap
        // no larger than the requested pitch; asymmetric windows are unchanged.
        if (minimum == -maximum && (intervals & 1) != 0) {
            intervals = Math.addExact(intervals, 1);
        }
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
                        + Math.multiplyExact(modeCount, AttemptMemoryLedger.objectBytes(128)),
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
                    mode.gradientTowardEdge(), mode.inwardExtentMeasured(),
                    mode.inwardSupportOffsetMeters(), mode.shoulderThreshold());
            censored.add(retainedOwner == null ? allocation.get()
                    : ProbabilisticInference.allocated(retainedOwner,
                        AttemptMemoryLedger.objectBytes(72), "sampled profiles", allocation::get));
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

    static double authorizedBoundary(MetricPoint anchor, MetricPoint normal, double direction,
        double maximum, double step, EvidenceSnapshot evidence, BoundaryWork work,
        AttemptMemoryLedger.Owner owner) {
        double requested = StrictMath.ceil(maximum / step);
        if (!Double.isFinite(requested) || requested > BoundaryWork.MAXIMUM_UNITS) {
            throw new ProfileBoundaryResourceLimitException();
        }
        work.admit((long) requested);
        double last = 0.0;
        for (long visit = 1; visit <= (long) requested; visit++) {
            work.cancellation.checkpoint();
            double bounded = visit == (long) requested ? maximum : Math.min(visit * step, maximum);
            if (!authorizedNormalSegment(anchor, normal, direction * last,
                    direction * bounded, evidence, work, owner)) {
                break;
            }
            last = bounded;
            if (bounded == maximum) {
                break;
            }
        }
        return direction * last;
    }

    private static boolean authorizedNormalSegment(MetricPoint anchor, MetricPoint normal,
            double from, double to, EvidenceSnapshot evidence, BoundaryWork work,
            AttemptMemoryLedger.Owner owner) {
        work.cancellation.checkpoint();
        work.admit(1L);
        AttemptMemoryLedger.Owner query = owner == null ? null : owner.child("normal-authority");
        try {
            if (query != null) query.reserve(2L * AttemptMemoryLedger.objectBytes(16));
            MetricPoint start = offset(anchor, normal, from);
            MetricPoint end = offset(anchor, normal, to);
            // Keep every ordered physical prefix and its complete edge/midpoint proof.
            // Charge event storage for observed events instead of allocating the polygon-edge bound.
            return evidence.decisionRegion().containsSegmentWithBoundedEvents(start, end, work.cancellation,
                    work::admit, query)
                && evidence.evidenceRegion().containsSegmentWithBoundedEvents(start, end, work.cancellation,
                    work::admit, query);
        } finally {
            if (query != null) query.close();
        }
    }

    /** A zero-width authorized prefix cannot supply a profile or movement evidence. */
    public static final class ProfileWindowUnavailableException extends IllegalArgumentException {
        private ProfileWindowUnavailableException(int index) {
            super("authorized profile window unavailable at profile " + index);
        }
    }

    /** Separate geometric authority work exhaustion, never a scalar or association refusal. */
    public static final class ProfileBoundaryResourceLimitException extends RuntimeException {
        private ProfileBoundaryResourceLimitException() {
            super("profile boundary geometry work limit");
        }
    }

    /** Geometry loop visits and an opaque-sort proxy, separate from scalar/feature budgets. */
    static final class BoundaryWork {
        static final long MAXIMUM_UNITS = 64_000_000L;
        private final CancellationProbe cancellation;
        private final long maximumUnits;
        private long units;

        BoundaryWork(CancellationProbe cancellation) { this(cancellation, MAXIMUM_UNITS); }

        BoundaryWork(CancellationProbe cancellation, long maximumUnits) {
            if (cancellation == null || maximumUnits <= 0L || maximumUnits > MAXIMUM_UNITS) {
                throw new IllegalArgumentException("Boundary work controls are invalid");
            }
            this.cancellation = cancellation;
            this.maximumUnits = maximumUnits;
        }

        void admit(long requested) {
            cancellation.checkpoint();
            if (requested < 0L || requested > maximumUnits - units) {
                throw new ProfileBoundaryResourceLimitException();
            }
            units += requested;
        }
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
     * Proves that adjacent localized centers retain directly measured lateral ownership between profiles.
     *
     * <p>Validity and the exact decision-region union are hard ownership boundaries. Inside those
     * boundaries scalar presence remains a separate upper bound. Local branch contrast is measured
     * relative to the weaker endpoint, so uniformly faint concentration is not attenuated twice.
     * Confidence deterioration is measured relative to both endpoints in the same candidate-normal
     * frame; original profile confidence remains independently represented in mode reliability.
     * A bridge losing its shoulders or agreed center approaches zero continuation continuously.</p>
     */
    static final class RasterIntervalEvidence
            implements LongitudinalModeReliability.DirectIntervalEvidence, LongConsumer {
        // Preserve the pre-existing scalar/crossed-cell admission cap. Feature work is separate.
        private static final long MAXIMUM_SAMPLING_UNITS = 2_000_000L;
        private static final long MAXIMUM_FEATURE_UNITS = 8_000_000L;
        private static final double EPSILON = 1e-12;

        private final EvidenceSnapshot evidence;
        private final ScalarEvidenceField field;
        private final EvidenceModelParameters.Localization localization;
        private final AttemptMemoryLedger.Owner owner;
        private long samplingUnits;
        private long scalarQueries;
        private long featureUnits;
        private long queries;
        private org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.YMembershipIndex decisionYIndex;
        private org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.YMembershipIndex capturedYIndex;

        private void prepareYMembership(CancellationProbe cancellation) {
            cancellation.checkpoint(); chargeFeatures(1L);
            if (decisionYIndex != null) return;
            AttemptMemoryLedger.Owner preparation = owner == null ? null : owner.child("bridge-region-membership");
            boolean complete = false;
            try {
                var decision = evidence.decisionRegion().prepareYMembership(cancellation, this, preparation);
                cancellation.checkpoint(); chargeFeatures(1L);
                var captured = evidence.decisionRegion() == evidence.evidenceRegion() ? decision
                        : evidence.evidenceRegion().prepareYMembership(cancellation, this, preparation);
                decisionYIndex = decision; capturedYIndex = captured;
                complete = true;
            } finally {
                if (!complete && preparation != null) preparation.close();
            }
        }

        RasterIntervalEvidence(EvidenceSnapshot evidence, ScalarEvidenceField field,
                EvidenceModelParameters.Localization localization,
                AttemptMemoryLedger.Owner owner) {
            this.evidence = evidence;
            this.field = field;
            this.localization = localization;
            this.owner = owner;
        }

        @Override
        public double support(ProbabilisticProfile leftProfile,
                ProbabilisticProfile.Mode leftMode, ProbabilisticProfile rightProfile,
                ProbabilisticProfile.Mode rightMode, CancellationProbe cancellation) {
            return supportInternal(leftProfile, leftMode, rightProfile, rightMode,
                    -1, null, cancellation);
        }

        /**
         * Measures one already selected RMS-suppressed endpoint candidate against its exterior
         * branch through the same raster checks as ordinary bridge support.
         */
        double supportRmsSuppressedPair(ProbabilisticProfile leftProfile,
                ProbabilisticProfile.Mode leftMode, ProbabilisticProfile rightProfile,
                ProbabilisticProfile.Mode rightMode, int suppressedProfileIndex,
                LocalScalarProfileExtractor.SuppressedMode suppressed,
                CancellationProbe cancellation) {
            if (suppressed == null || cancellation == null || leftProfile == null || rightProfile == null
                    || leftMode == null || rightMode == null || suppressedProfileIndex < 0) return 0.0;
            cancellation.checkpoint();
            boolean leftSuppressed = leftProfile.profileIndex() == suppressedProfileIndex;
            boolean rightSuppressed = rightProfile.profileIndex() == suppressedProfileIndex;
            if (leftSuppressed == rightSuppressed) return 0.0;
            ProbabilisticProfile candidateProfile = leftSuppressed ? leftProfile : rightProfile;
            ProbabilisticProfile.Mode candidateMode = leftSuppressed ? leftMode : rightMode;
            ProbabilisticProfile exteriorProfile = leftSuppressed ? rightProfile : leftProfile;
            ProbabilisticProfile.Mode exteriorMode = leftSuppressed ? rightMode : leftMode;
            boolean exteriorModePresent = false;
            for (ProbabilisticProfile.Mode mode : exteriorProfile.modes()) {
                cancellation.checkpoint();
                chargeFeatures(1L);
                if (mode == exteriorMode || mode.equals(exteriorMode)) exteriorModePresent = true;
            }
            if (!candidateProfile.nativePitchKnown() || !exteriorProfile.nativePitchKnown()
                    || suppressed.reason() != LocalScalarProfileExtractor.SuppressionReason.RMS_ONLY
                    || !sameModeShape(candidateMode, suppressed.mode())
                    || !exteriorModePresent
                    || !offsetWithin(candidateProfile, suppressed.rawPeakOffsetMeters())
                    || !offsetWithin(candidateProfile, suppressed.mode().coreMinimumMeters())
                    || !offsetWithin(candidateProfile, suppressed.mode().coreMaximumMeters())) return 0.0;
            return supportInternal(leftProfile, leftMode, rightProfile, rightMode,
                    suppressedProfileIndex, suppressed, cancellation);
        }

        /**
         * Observes only a resolution-bounded scalar inequality between censored bands.
         * This is neither a center/strand proof nor direct route support; no bridge
         * receipt or measured-mode confidence is produced.
         */
        boolean observedCensoredBand(ProbabilisticProfile leftProfile,
                ProbabilisticProfile.CensoredMode leftBand, ProbabilisticProfile rightProfile,
                ProbabilisticProfile.CensoredMode rightBand, CancellationProbe cancellation) {
            if (cancellation == null) throw new IllegalArgumentException("Cancellation control is required");
            cancellation.checkpoint(); chargeFeatures(1L);
            if (leftProfile == null || rightProfile == null || leftBand == null || rightBand == null
                    || leftBand.side() != rightBand.side()
                    || !leftBand.gradientSupported() || !rightBand.gradientSupported()
                    || !(leftBand.existenceConfidence() > 0) || !(rightBand.existenceConfidence() > 0)
                    || !leftBand.inwardExtentMeasured() || !rightBand.inwardExtentMeasured()
                    || !Double.isFinite(leftBand.shoulderThreshold()) || !Double.isFinite(rightBand.shoulderThreshold())
                    || rightProfile.profileIndex() != leftProfile.profileIndex()+1
                    || !(rightProfile.chainageMeters() > leftProfile.chainageMeters())
                    || rightProfile.chainageMeters() - leftProfile.chainageMeters()
                        > LongitudinalModeReliability.WINDOW_METERS) return false;
            int leftMatches = 0, rightMatches = 0;
            for (int i = 0; i < leftProfile.censoredModes().size(); i++) {
                cancellation.checkpoint(); chargeFeatures(1L);
                if (leftProfile.censoredModes().get(i) == leftBand) leftMatches++;
            }
            for (int i = 0; i < rightProfile.censoredModes().size(); i++) {
                cancellation.checkpoint(); chargeFeatures(1L);
                if (rightProfile.censoredModes().get(i) == rightBand) rightMatches++;
            }
            if (leftMatches != 1 || rightMatches != 1
                    || !offsetWithin(leftProfile, leftBand.observedBoundaryMeters())
                    || !offsetWithin(leftProfile, leftBand.inwardSupportOffsetMeters())
                    || !offsetWithin(rightProfile, rightBand.observedBoundaryMeters())
                    || !offsetWithin(rightProfile, rightBand.inwardSupportOffsetMeters())) return false;
            double lx = leftProfile.anchor().xMeters() + leftProfile.normalUnit().xMeters()*leftBand.observedBoundaryMeters();
            double ly = leftProfile.anchor().yMeters() + leftProfile.normalUnit().yMeters()*leftBand.observedBoundaryMeters();
            double rx = rightProfile.anchor().xMeters() + rightProfile.normalUnit().xMeters()*rightBand.observedBoundaryMeters();
            double ry = rightProfile.anchor().yMeters() + rightProfile.normalUnit().yMeters()*rightBand.observedBoundaryMeters();
            double distance = StrictMath.hypot(rx-lx, ry-ly);
            if (!(distance > 0) || !Double.isFinite(distance)) return false;
            double rasterSpan = evidence.transform().maximumRasterAxisSpan(lx, ly, rx, ry);
            if (!Double.isFinite(rasterSpan)) return false;
            long intervals = Math.max(1L, (long) StrictMath.ceil(2*rasterSpan));
            if (intervals >= MAXIMUM_SAMPLING_UNITS) chargeSampling(MAXIMUM_SAMPLING_UNITS+1L);
            chargeSampling(intervals+1L); // Admit the complete scalar loop before scratch allocation.
            var queryOwner = owner == null ? null : owner.child("censored-band-scalar-link");
            try (AttemptMemoryLedger.Reservation pointScratch = queryOwner == null ? null
                    : queryOwner.reserve(3L*AttemptMemoryLedger.objectBytes(16) + 512L)) {
                prepareYMembership(cancellation);
                var left = new MetricPoint(lx,ly); var right = new MetricPoint(rx,ry);
                if (!evidence.decisionRegion().containsSegmentWithBoundedEvents(left,right,cancellation,
                        this,queryOwner,decisionYIndex)
                        || !capturedSegmentSupported(left,right,cancellation,queryOwner)) return false;
                long pointMembershipWork = 0L;
                for (int i = 0; i < evidence.decisionRegion().polygons().size(); i++) {
                    cancellation.checkpoint(); chargeFeatures(1L);
                    pointMembershipWork = Math.addExact(pointMembershipWork,
                            evidence.decisionRegion().polygons().get(i).size());
                }
                for (long i = 0; i <= intervals; i++) {
                    cancellation.checkpoint(); chargeFeatures(1L+pointMembershipWork);
                    double fraction = (double)i/intervals;
                    var point = new MetricPoint(lx+fraction*(rx-lx), ly+fraction*(ry-ly));
                    scalarQueries++;
                    OptionalDouble value = StrictScalarSampler.sample(field,evidence.transform(),evidence.decisionRegion(),point);
                    double threshold = leftBand.shoulderThreshold()
                            + fraction*(rightBand.shoulderThreshold()-leftBand.shoulderThreshold());
                    if (value.isEmpty() || !Double.isFinite(value.getAsDouble())
                            || value.getAsDouble()+EPSILON < threshold) return false;
                }
                return true;
            } finally { if (queryOwner != null) queryOwner.close(); }
        }

        private double supportInternal(ProbabilisticProfile leftProfile,
                ProbabilisticProfile.Mode leftMode, ProbabilisticProfile rightProfile,
                ProbabilisticProfile.Mode rightMode, int suppressedProfileIndex,
                LocalScalarProfileExtractor.SuppressedMode suppressed,
                CancellationProbe cancellation) {
            if (leftProfile == null || leftMode == null || rightProfile == null || rightMode == null
                    || cancellation == null) return 0.0;
            boolean leftSuppressed = suppressed != null
                    && leftProfile.profileIndex() == suppressedProfileIndex;
            boolean rightSuppressed = suppressed != null
                    && rightProfile.profileIndex() == suppressedProfileIndex;
            MetricPoint left = leftSuppressed ? modeCenter(leftProfile, suppressed.mode().centerMeters())
                    : modeCenter(leftProfile, leftMode);
            MetricPoint right = rightSuppressed ? modeCenter(rightProfile, suppressed.mode().centerMeters())
                    : modeCenter(rightProfile, rightMode);
            if (!evidence.routeSegmentAuthorized(left, right)) {
                return 0.0;
            }
            double distance = left.distanceTo(right);
            if (!(distance > 0.0)) {
                return 0.0;
            }
            double leftProminence = leftSuppressed
                    ? directProminence(leftProfile, suppressed.mode()) : directProminence(leftProfile, leftMode);
            double rightProminence = rightSuppressed
                    ? directProminence(rightProfile, suppressed.mode()) : directProminence(rightProfile, rightMode);
            double referenceProminence = Math.min(leftProminence, rightProminence);
            double leftAmplitude = leftSuppressed ? suppressed.mode().scalarAmplitudeReliability()
                    : leftMode.scalarAmplitudeReliability();
            double rightAmplitude = rightSuppressed ? suppressed.mode().scalarAmplitudeReliability()
                    : rightMode.scalarAmplitudeReliability();
            double referenceAmplitude = Math.min(leftAmplitude, rightAmplitude);
            if (!(referenceProminence > EPSILON) || !(referenceAmplitude > 0.0)) {
                return 0.0;
            }
            if (!supportsEveryCrossedInterpolationCell(field, evidence.transform(), left, right,
                    cancellation, this::chargeSampling, owner)) {
                return 0.0;
            }
            double samplingStep = 0.5 * evidence.resolution().outputRasterPitchMeters();
            long intervals = Math.max(1L, (long) Math.ceil(distance / samplingStep));
            chargeSampling(intervals + 1L);
            double minimumRatio = 1.0;
            MetricPoint tangent = new MetricPoint(right.xMeters() - left.xMeters(),
                    right.yMeters() - left.yMeters());
            // Only three scalar summaries survive a closed query: two endpoints and one current row.
            try (AttemptMemoryLedger.Reservation summaries = owner == null ? null
                    : owner.reserve(3L * AttemptMemoryLedger.objectBytes(16))) {
            BridgeMeasurement leftMeasurement = localizedSupport(left, tangent,
                    leftProfile.sourcePitchMeters(), cancellation,
                    leftSuppressed ? suppressed : null, suppressed != null && leftSuppressed);
            if (leftMeasurement == null) return 0.0;
            BridgeMeasurement rightMeasurement = localizedSupport(right, tangent,
                    rightProfile.sourcePitchMeters(), cancellation,
                    rightSuppressed ? suppressed : null, suppressed != null && rightSuppressed);
            if (rightMeasurement == null) return 0.0;
            double referenceConfidence = Math.min(leftMeasurement.confidence(), rightMeasurement.confidence());
            for (long index = 0L; index <= intervals; index++) {
                cancellation.checkpoint();
                double fraction = (double) index / intervals;
                MetricPoint point = new MetricPoint(
                        left.xMeters() + fraction * (right.xMeters() - left.xMeters()),
                        left.yMeters() + fraction * (right.yMeters() - left.yMeters()));
                scalarQueries++;
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
                double sourcePitch = leftProfile.sourcePitchMeters() + fraction
                        * (rightProfile.sourcePitchMeters() - leftProfile.sourcePitchMeters());
                BridgeMeasurement measurement = index == 0L ? leftMeasurement
                        : index == intervals ? rightMeasurement
                        : localizedSupport(point, tangent, sourcePitch, cancellation,
                                null, suppressed != null);
                if (measurement == null) return 0.0;
                double localized = Math.min(1.0, measurement.confidence() / referenceConfidence)
                        * Math.min(1.0, measurement.amplitudeReliability() / referenceAmplitude);
                if (!(localized > 0.0)) return 0.0;
                minimumRatio = Math.min(minimumRatio, localized);
            }
            return Math.min(1.0, minimumRatio);
            }
        }

        private BridgeMeasurement localizedSupport(MetricPoint point, MetricPoint tangent, double sourcePitch,
                CancellationProbe cancellation) {
            return localizedSupport(point, tangent, sourcePitch, cancellation, null, false);
        }

        private BridgeMeasurement localizedSupport(MetricPoint point, MetricPoint tangent, double sourcePitch,
                CancellationProbe cancellation, LocalScalarProfileExtractor.SuppressedMode suppressed,
                boolean candidateProof) {
            double halfWidth = Math.max(localization.routeProfileHalfWidthMeters(), 4.0 * sourcePitch);
            double requested = StrictMath.ceil(2.0 * halfWidth / (0.5 * sourcePitch));
            if (!Double.isFinite(requested) || requested > MAXIMUM_SAMPLING_UNITS - 1L) {
                throw new LongitudinalModeReliability.ResourceLimitException();
            }
            long intervals = Math.max(4L, (long) requested);
            if ((intervals & 1L) != 0L) intervals++;
            long sampleCount = Math.addExact(intervals, 1L);
            chargeSampling(sampleCount); // Complete lateral admission precedes list or sample allocation.
            prepareYMembership(cancellation);
            queries++;
            cancellation.checkpoint();
            AttemptMemoryLedger.Owner queryOwner = owner == null ? null
                    : owner.child("bridge-local-profile");
            // Conservative simultaneous roles: first/last normals plus up to three
            // closing-path points, two transformed raster points, one scalar optional.
            // Segment/event containers retain their own separately owned scratch.
            try (AttemptMemoryLedger.Reservation queryScratch = queryOwner == null ? null
                    : queryOwner.reserve(5L * AttemptMemoryLedger.objectBytes(16)
                        + 2L * AttemptMemoryLedger.objectBytes(16)
                        + AttemptMemoryLedger.objectBytes(9))) {
                int count = Math.toIntExact(sampleCount);
                long bytes = Math.addExact(ProbabilisticInference.listBytes(sampleCount),
                        Math.multiplyExact(sampleCount, AttemptMemoryLedger.objectBytes(24)));
                List<LocalScalarProfileExtractor.Sample> samples = queryOwner == null
                        ? new ArrayList<>(count)
                        : ProbabilisticInference.allocated(queryOwner, bytes, "sampled profiles",
                                () -> new ArrayList<>(count));
                double tangentLength = StrictMath.hypot(tangent.xMeters(), tangent.yMeters());
                double normalX = -tangent.yMeters() / tangentLength;
                double normalY = tangent.xMeters() / tangentLength;
                for (long index = 0L; index <= intervals; index++) {
                        cancellation.checkpoint();
                        double offset = -halfWidth + 2.0 * halfWidth * index / intervals;
                        MetricPoint location = new MetricPoint(point.xMeters() + normalX * offset,
                                point.yMeters() + normalY * offset);
                        // Halo values cannot supply either a center or a below-threshold bracket.
                        scalarQueries++;
                        OptionalDouble value = StrictScalarSampler.sample(field, evidence.transform(),
                                evidence.decisionRegion(), location);
                        samples.add(new LocalScalarProfileExtractor.Sample(offset,
                                value.orElse(Double.NaN), value.isPresent()));
                }
                LocalScalarProfileExtractor extractor = new LocalScalarProfileExtractor();
                MetricPoint first = normalPoint(point, normalX, normalY, samples.get(0).offsetMeters());
                MetricPoint last = normalPoint(point, normalX, normalY, samples.get(count - 1).offsetMeters());
                boolean decisionComplete = evidence.decisionRegion().containsSegmentWithBoundedEvents(first, last,
                        cancellation, this, queryOwner, decisionYIndex);
                boolean sourceComplete = capturedSegmentSupported(first, last, cancellation, queryOwner);
                if (decisionComplete && sourceComplete && !candidateProof && suppressed == null) {
                    // A continuous authorized/source-valid normal cannot gain different context
                    // samples. Preserve the ordinary extraction and measurement arithmetic.
                    LocalScalarProfileExtractor.Result features = extractor.extract(samples, sourcePitch,
                            localization, queryOwner, cancellation, this::chargeFeatures);
                    LocalScalarProfileExtractor.Mode mode = ownedBridgeMode(features,
                            cancellation, this::chargeFeatures);
                    return mode == null ? null
                            : new BridgeMeasurement(mode.localizationConfidence(), mode.scalarAmplitudeReliability());
                }
                LocalScalarProfileExtractor.DetailedResult features = extractor.extractDetailed(samples,
                        sourcePitch, localization, queryOwner, cancellation, this::chargeFeatures);
                LocalScalarProfileExtractor.Mode mode = candidateProof || suppressed != null
                        ? ownedDetailedBridgeMode(features, suppressed, candidateProof,
                            cancellation, this::chargeFeatures)
                        : ownedBridgeMode(features.baseline(), cancellation, this::chargeFeatures);
                if (mode == null) return null;
                BridgeMeasurement original = new BridgeMeasurement(mode.localizationConfidence(),
                        mode.scalarAmplitudeReliability());
                if (decisionComplete && sourceComplete) return original;
                LocalScalarProfileExtractor.RawCoreReceipt receipt = exactRawCoreReceipt(features, mode,
                        cancellation);
                if (receipt != null && !originalClosureAuthorized(receipt, samples, point,
                        normalX, normalY, cancellation, queryOwner)) return null;
                // Identical decision/context authorization cannot create a negative witness.
                // Missing unrelated captured support is UNKNOWN, never replacement evidence.
                if (decisionComplete || !sourceComplete || receipt == null) return original;

                chargeSampling(sampleCount); // Same cumulative cap; no admission reset for context.
                List<LocalScalarProfileExtractor.Sample> contextSamples = queryOwner == null
                        ? new ArrayList<>(count)
                        : ProbabilisticInference.allocated(queryOwner, bytes, "sampled profiles",
                                () -> new ArrayList<>(count));
                for (int index = 0; index < count; index++) {
                    cancellation.checkpoint();
                    double offset = samples.get(index).offsetMeters();
                    MetricPoint location = normalPoint(point, normalX, normalY, offset);
                    scalarQueries++;
                    OptionalDouble value = StrictScalarSampler.sample(field, evidence.transform(),
                            evidence.evidenceRegion(), location);
                    contextSamples.add(new LocalScalarProfileExtractor.Sample(offset,
                            value.orElse(Double.NaN), value.isPresent()));
                }
                var context = extractor.projectContextVeto(contextSamples, sourcePitch, localization,
                        queryOwner, cancellation, this::chargeFeatures, receipt.rawPeak());
                ProbabilisticRawCoreContextVeto.DecisionPathAuthority authority = queryOwner == null
                        ? (winner, bracket) -> provedOrMeasuredDecisionPathAuthorized(receipt, samples, winner, bracket,
                            point, normalX, normalY, cancellation, null)
                        : ProbabilisticInference.allocated(queryOwner, AttemptMemoryLedger.objectBytes(64),
                            "sampled profiles", () -> (winner, bracket) -> provedOrMeasuredDecisionPathAuthorized(receipt, samples,
                                winner, bracket, point, normalX, normalY, cancellation, queryOwner));
                return ProbabilisticRawCoreContextVeto.contradicts(samples, contextSamples,
                        features, context, mode, cancellation, this::chargeFeatures, authority) ? null : original;
            } finally {
                if (queryOwner != null) queryOwner.close();
            }
        }

        private static MetricPoint normalPoint(MetricPoint point, double normalX, double normalY,
                double offset) {
            return new MetricPoint(point.xMeters() + normalX * offset, point.yMeters() + normalY * offset);
        }

        private boolean capturedSegmentSupported(MetricPoint first, MetricPoint last,
                CancellationProbe cancellation, AttemptMemoryLedger.Owner queryOwner) {
            return evidence.evidenceRegion().containsSegmentWithBoundedEvents(first, last, cancellation,
                    this, queryOwner, capturedYIndex)
                    && supportsEveryCrossedInterpolationCell(field, evidence.transform(), first, last,
                        cancellation, this::chargeFeatures, queryOwner);
        }

        private boolean decisionPathAuthorized(List<LocalScalarProfileExtractor.Sample> samples,
                int winner, int bracket, MetricPoint point, double normalX, double normalY,
                CancellationProbe cancellation, AttemptMemoryLedger.Owner queryOwner) {
            return evidence.decisionRegion().containsSegmentWithBoundedEvents(
                    normalPoint(point, normalX, normalY, samples.get(winner).offsetMeters()),
                    normalPoint(point, normalX, normalY, samples.get(bracket).offsetMeters()),
                    cancellation, this, queryOwner, decisionYIndex);
        }

        private boolean provedOrMeasuredDecisionPathAuthorized(LocalScalarProfileExtractor.RawCoreReceipt proved,
                List<LocalScalarProfileExtractor.Sample> samples, int winner, int bracket,
                MetricPoint point, double normalX, double normalY, CancellationProbe cancellation,
                AttemptMemoryLedger.Owner queryOwner) {
            // Any nonnull receipt reaches this callback only after its full
            // originalClosureAuthorized success. Its samples/frame/D identity
            // remain the same borrowed query inputs; no receipt survives query close.
            if (matchesProvedOriginalDecisionPath(proved, winner, bracket, cancellation, this)) return true;
            return decisionPathAuthorized(samples, winner, bracket, point, normalX, normalY,
                    cancellation, queryOwner);
        }

        /** Matches only literal ordered tuples of an already fully proved original receipt. */
        static boolean matchesProvedOriginalDecisionPath(LocalScalarProfileExtractor.RawCoreReceipt proved,
                int winner, int bracket, CancellationProbe cancellation, LongConsumer charge) {
            cancellation.checkpoint(); charge.accept(1L);
            if (proved == null) return false;
            cancellation.checkpoint(); charge.accept(1L);
            if (winner != proved.rawStageWinnerSampleIndex()) return false;
            cancellation.checkpoint(); charge.accept(2L);
            return bracket == proved.leftAdjacentSampleIndex() || bracket == proved.rightAdjacentSampleIndex();
        }

        @Override public void accept(long units) { chargeFeatures(units); }

        private LocalScalarProfileExtractor.RawCoreReceipt exactRawCoreReceipt(
                LocalScalarProfileExtractor.DetailedResult detailed, LocalScalarProfileExtractor.Mode mode,
                CancellationProbe cancellation) {
            LocalScalarProfileExtractor.RawCoreReceipt selected = null;
            int matches = 0;
            for (var receipt : detailed.rawCoreReceipts()) {
                cancellation.checkpoint();
                chargeFeatures(1L);
                if (receipt.mode() == mode) { selected = receipt; matches++; }
            }
            return matches == 1 ? selected : null;
        }

        private boolean originalClosureAuthorized(LocalScalarProfileExtractor.RawCoreReceipt receipt,
                List<LocalScalarProfileExtractor.Sample> samples, MetricPoint point,
                double normalX, double normalY, CancellationProbe cancellation,
                AttemptMemoryLedger.Owner queryOwner) {
            if (!receipt.leftAdjacentObservedValid() || !receipt.leftAdjacentBelowCore()
                    || !receipt.rightAdjacentObservedValid() || !receipt.rightAdjacentBelowCore()) return false;
            if (!decisionPathAuthorized(samples, receipt.rawStageWinnerSampleIndex(),
                    receipt.leftAdjacentSampleIndex(), point, normalX, normalY, cancellation, queryOwner)
                    || !decisionPathAuthorized(samples, receipt.rawStageWinnerSampleIndex(),
                        receipt.rightAdjacentSampleIndex(), point, normalX, normalY, cancellation, queryOwner)) {
                return false;
            }
            MetricPoint winner = normalPoint(point, normalX, normalY,
                    samples.get(receipt.rawStageWinnerSampleIndex()).offsetMeters());
            return capturedSegmentSupported(winner, normalPoint(point, normalX, normalY,
                        samples.get(receipt.leftAdjacentSampleIndex()).offsetMeters()), cancellation, queryOwner)
                    && capturedSegmentSupported(winner, normalPoint(point, normalX, normalY,
                        samples.get(receipt.rightAdjacentSampleIndex()).offsetMeters()), cancellation, queryOwner);
        }

        /** Pure ownership seam; no adjacent/parallel component may replace a missing measured center. */
        static double measuredBridgeFactor(LocalScalarProfileExtractor.Result features,
                double referenceAmplitude, CancellationProbe cancellation, LongConsumer charge) {
            return measuredBridgeFactor(features, referenceAmplitude, 1.0, cancellation, charge);
        }

        static double measuredBridgeFactor(LocalScalarProfileExtractor.Result features,
                double referenceAmplitude, double referenceConfidence,
                CancellationProbe cancellation, LongConsumer charge) {
            LocalScalarProfileExtractor.Mode selected = ownedBridgeMode(features, cancellation, charge);
            if (selected == null) return 0.0;
            return Math.min(1.0, selected.localizationConfidence() / referenceConfidence)
                    * Math.min(1.0, selected.scalarAmplitudeReliability() / referenceAmplitude);
        }

        private static LocalScalarProfileExtractor.Mode ownedBridgeMode(LocalScalarProfileExtractor.Result features,
                CancellationProbe cancellation, LongConsumer charge) {
                LocalScalarProfileExtractor.Mode selected = null;
                double nearest = Double.POSITIVE_INFINITY;
                boolean tied = false;
                for (LocalScalarProfileExtractor.Mode mode : features.modes()) {
                    cancellation.checkpoint();
                    charge.accept(1L);
                    double distance = mode.distanceToCenterSet(0.0);
                    if (distance < nearest) {
                        selected = mode;
                        nearest = distance;
                        tied = false;
                    } else if (distance == nearest) {
                        tied = true;
                    }
                }
                if (selected == null || tied || !(selected.localizationConfidence() > 0.0)
                        || nearest > selected.localizationSigmaMeters() + EPSILON) return null;
                for (LocalScalarProfileExtractor.CensoredMode mode : features.censoredModes()) {
                    cancellation.checkpoint();
                    charge.accept(1L);
                    if (Math.abs(mode.boundaryOffsetMeters()) <= nearest) return null;
                }
                return selected;
        }

        private static LocalScalarProfileExtractor.Mode ownedDetailedBridgeMode(
                LocalScalarProfileExtractor.DetailedResult detailed,
                LocalScalarProfileExtractor.SuppressedMode expected, boolean candidateProof,
                CancellationProbe cancellation,
                LongConsumer charge) {
            LocalScalarProfileExtractor.Mode selected;
            if (expected == null) {
                if (candidateProof) {
                    DetailedCoreOwnership ownership = uniqueMeasuredHighCoreAtZero(detailed, cancellation, charge);
                    selected = ownership == null ? null : ownership.mode();
                } else {
                    selected = ownedBridgeMode(detailed.baseline(), cancellation, charge);
                }
            } else {
                DetailedCoreOwnership ownership = uniqueMeasuredHighCoreAtZero(detailed, cancellation, charge);
                if (ownership == null || !ownership.rmsSuppressed()) return null;
                selected = ownership.mode();
            }
            if (selected == null || !hasExactTwoSidedFlankReceipt(detailed, selected, cancellation, charge)) {
                return null;
            }
            return selected;
        }

        private static boolean hasExactTwoSidedFlankReceipt(
                LocalScalarProfileExtractor.DetailedResult detailed,
                LocalScalarProfileExtractor.Mode selected, CancellationProbe cancellation, LongConsumer charge) {
            LocalScalarProfileExtractor.FlankReceipt receipt = null;
            for (LocalScalarProfileExtractor.FlankReceipt candidate : detailed.flankReceipts()) {
                cancellation.checkpoint();
                charge.accept(1L);
                if (candidate.mode() == selected) {
                    if (receipt != null) return false;
                    receipt = candidate;
                }
            }
            return receipt != null && receipt.hasTwoSidedValidOutwardContinuation();
        }

        static DetailedCoreOwnership uniqueMeasuredHighCoreAtZero(
                LocalScalarProfileExtractor.DetailedResult detailed, CancellationProbe cancellation,
                LongConsumer charge) {
            LocalScalarProfileExtractor.Mode selected = null;
            boolean selectedSuppressed = false;
            int owners = 0;
            for (LocalScalarProfileExtractor.Mode mode : detailed.baseline().modes()) {
                cancellation.checkpoint();
                charge.accept(1L);
                if (ownsHighCoreAtZero(mode)) {
                    owners++;
                    selected = mode;
                    selectedSuppressed = false;
                }
            }
            for (LocalScalarProfileExtractor.SuppressedMode mode : detailed.suppressedModes()) {
                cancellation.checkpoint();
                charge.accept(1L);
                if (ownsHighCoreAtZero(mode.mode())) {
                    owners++;
                    selected = mode.mode();
                    selectedSuppressed = true;
                }
            }
            if (owners != 1 || selected == null
                    || !(selected.localizationConfidence() > 0.0)) return null;
            for (LocalScalarProfileExtractor.CensoredMode mode : detailed.baseline().censoredModes()) {
                cancellation.checkpoint();
                charge.accept(1L);
                double minimum = Math.min(mode.boundaryOffsetMeters(), mode.inwardSupportOffsetMeters());
                double maximum = Math.max(mode.boundaryOffsetMeters(), mode.inwardSupportOffsetMeters());
                if (minimum <= EPSILON && maximum >= -EPSILON) return null;
            }
            return new DetailedCoreOwnership(selected, selectedSuppressed);
        }

        static boolean ownsHighCoreAtZero(LocalScalarProfileExtractor.Mode mode) {
            return mode.localizationConfidence() > 0.0
                    && mode.coreMinimumMeters() <= EPSILON
                    && mode.coreMaximumMeters() >= -EPSILON;
        }

        private static boolean sameModeShape(ProbabilisticProfile.Mode profileMode,
                LocalScalarProfileExtractor.Mode scalarMode) {
            return same(profileMode.coreMinimumMeters(), scalarMode.coreMinimumMeters())
                    && same(profileMode.coreMaximumMeters(), scalarMode.coreMaximumMeters())
                    && same(profileMode.localizationSigmaMeters(), scalarMode.localizationSigmaMeters())
                    && same(profileMode.existenceConfidence(), scalarMode.existenceConfidence())
                    && same(profileMode.localizationConfidence(), scalarMode.localizationConfidence())
                    && profileMode.peakOffsetsMeters().equals(scalarMode.peakOffsetsMeters())
                    && profileMode.nestedCenterOffsetsMeters().equals(scalarMode.nestedCenterOffsetsMeters())
                    && same(profileMode.scalarAmplitudeReliability(), scalarMode.scalarAmplitudeReliability());
        }

        private static boolean same(double left, double right) {
            return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
        }

        private static boolean offsetWithin(ProbabilisticProfile profile, double offset) {
            return offset >= profile.minimumOffsetMeters() - EPSILON
                    && offset <= profile.maximumOffsetMeters() + EPSILON;
        }

        private record BridgeMeasurement(double confidence, double amplitudeReliability) { }

        record DetailedCoreOwnership(LocalScalarProfileExtractor.Mode mode, boolean rmsSuppressed) { }

        private void chargeSampling(long requested) {
            if (requested < 0L || requested > MAXIMUM_SAMPLING_UNITS - samplingUnits) {
                throw new LongitudinalModeReliability.ResourceLimitException();
            }
            samplingUnits += requested;
        }

        private void chargeFeatures(long requested) {
            if (requested < 0L || requested > MAXIMUM_FEATURE_UNITS - featureUnits) {
                throw new LongitudinalModeReliability.ResourceLimitException();
            }
            featureUnits += requested;
        }

        private static MetricPoint modeCenter(ProbabilisticProfile profile,
                ProbabilisticProfile.Mode mode) {
            return modeCenter(profile, mode.coreCenterMeters());
        }

        private static MetricPoint modeCenter(ProbabilisticProfile profile, double offset) {
            return new MetricPoint(
                    profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset,
                    profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset);
        }

        private static double directProminence(ProbabilisticProfile profile,
                ProbabilisticProfile.Mode mode) {
            return directProminence(profile, mode.coreMinimumMeters(), mode.coreMaximumMeters());
        }

        private static double directProminence(ProbabilisticProfile profile,
                LocalScalarProfileExtractor.Mode mode) {
            return directProminence(profile, mode.coreMinimumMeters(), mode.coreMaximumMeters());
        }

        private static double directProminence(ProbabilisticProfile profile,
                double coreMinimumMeters, double coreMaximumMeters) {
            double tolerance = 1e-9 * Math.max(1.0, profile.sourcePitchMeters());
            double maximum = 0.0;
            for (ProbabilisticProfile.Sample sample : profile.samples()) {
                if (sample.valid()
                        && sample.offsetMeters() + tolerance >= coreMinimumMeters
                        && sample.offsetMeters() - tolerance <= coreMaximumMeters) {
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
