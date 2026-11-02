package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RetainedScalarProfiles;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ScalarFieldGridView;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ScalarIntensityField;

/** Immutable source facts; neither factual eligibility nor this context grants admitted execution. */
public final class OptionalEmissionContext {
    public static final String POLICY_ID = "optional-emission-native-integral-v2";
    public enum Origin { DETACHED_FROZEN, DECLARED_SYNTHETIC_METRIC_GRID }
    public enum Eligibility { FACTUAL_DETACHED, DECLARED_SYNTHETIC, SYNTHETIC_SOURCE,
        NATIVE_PITCH_UNAVAILABLE, FILTERED_SOURCE }
    /** Original observation identity within this source binding, with a frozen normal. */
    public record Observation(int index, DetachedProfileSamplingLocation location, RasterPoint normal) { }

    private final ScalarIntensityField nativeField;
    private final ScalarFieldGridView nativeGrid;
    private final List<Observation> observations;
    private final MultiScaleProfileSet producedProfiles;
    private final MetricRegion decisionRegion;
    private final MetricRegion evidenceRegion;
    private final RasterMetricTransform rasterTransform;
    private final LocalMetricFrame coordinateFrame;
    private final EvidenceResolution resolution;
    private final EvidenceFieldLineage lineage;
    private final ProfileChainage chainage;
    private final double searchHalfWidthMeters;
    private final double lateralStepMeters;
    private final OptionalDouble nativePitchMeters;
    private final String sourceIdentity;
    private final String evidenceContentHash;
    private final String requestIdentity;
    private final Optional<TraceBudgets> originalBudgets;
    private final Origin origin;
    private final Eligibility eligibility;

    OptionalEmissionContext(OptionalEmissionContextFactory.SourceBinding binding, RetainedScalarProfiles retained) {
        nativeField = retained.levelZero();
        nativeGrid = ScalarFieldGridView.fromIntensity(nativeField);
        producedProfiles = retained.profiles();
        decisionRegion = binding.source().decisionRegion();
        evidenceRegion = binding.source().evidenceRegion();
        rasterTransform = binding.source().transform();
        coordinateFrame = binding.source().coordinateFrame();
        resolution = binding.source().resolution();
        lineage = binding.source().fields().get(binding.fieldName()).lineage();
        chainage = binding.chainage();
        searchHalfWidthMeters = binding.halfWidth();
        lateralStepMeters = binding.lateralStep();
        sourceIdentity = binding.source().sourceIdentity();
        evidenceContentHash = binding.contentHash();
        requestIdentity = binding.requestIdentity();
        originalBudgets = binding.originalBudgets();
        origin = binding.origin();
        List<Observation> facts = new ArrayList<>(retained.profileLocations().size());
        double maximumNativePitch = 0;
        for (int index = 0; index < retained.profileLocations().size(); index++) {
            binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var location = retained.profileLocations().get(index);
            var previous = retained.profileLocations().get(Math.max(0, index - 1)).rasterPoint();
            var next = retained.profileLocations().get(Math.min(retained.profileLocations().size() - 1,
                index + 1)).rasterPoint();
            var tangent = PolylineMath.normalize(next.x() - previous.x(), next.y() - previous.y());
            facts.add(new Observation(index, location, new RasterPoint(-tangent.y, tangent.x)));
            if (resolution.nativePitchMeters().isPresent()) {
                maximumNativePitch = Math.max(maximumNativePitch, resolution.effectivePitchMetersAt(
                    chainage.sourceOriginGroundMeters() + location.cumulativeGroundDistanceMeters()));
            }
        }
        observations = List.copyOf(facts);
        nativePitchMeters = resolution.nativePitchMeters().isPresent()
            ? OptionalDouble.of(maximumNativePitch) : OptionalDouble.empty();
        eligibility = origin == Origin.DECLARED_SYNTHETIC_METRIC_GRID ? Eligibility.DECLARED_SYNTHETIC
            : nativePitchMeters.isEmpty() ? Eligibility.NATIVE_PITCH_UNAVAILABLE
            : lineage.acquisitionKind() == EvidenceFieldLineage.AcquisitionKind.SYNTHETIC
                || lineage.correlationGroup() == EvidenceCorrelationGroup.SYNTHETIC_TRUTH
                ? Eligibility.SYNTHETIC_SOURCE
            : lineage.filtered() ? Eligibility.FILTERED_SOURCE : Eligibility.FACTUAL_DETACHED;
        requireProfilesUnchanged(producedProfiles);
    }

    public ScalarIntensityField nativeField() { return nativeField; }
    public ScalarFieldGridView nativeGrid() { return nativeGrid; }
    public List<Observation> observations() { return observations; }
    public MetricRegion decisionRegion() { return decisionRegion; }
    public MetricRegion evidenceRegion() { return evidenceRegion; }
    public RasterMetricTransform rasterTransform() { return rasterTransform; }
    public LocalMetricFrame coordinateFrame() { return coordinateFrame; }
    public EvidenceResolution resolution() { return resolution; }
    public EvidenceFieldLineage lineage() { return lineage; }
    public ProfileChainage profileChainage() { return chainage; }
    public double searchHalfWidthMeters() { return searchHalfWidthMeters; }
    public double lateralStepMeters() { return lateralStepMeters; }
    /** Maximum factual native pitch on the bound source interval; rendered fallback is never called native. */
    public OptionalDouble nativePitchMeters() { return nativePitchMeters; }
    public double outputRasterPitchMeters() { return resolution.outputRasterPitchMeters(); }
    /** Detached producer coordinates already use the L0 raster frame. */
    public double profileCoordinateScale() { return 1.0; }
    public String sourceIdentity() { return sourceIdentity; }
    public String evidenceContentHash() { return evidenceContentHash; }
    public String requestIdentity() { return requestIdentity; }
    public Optional<TraceBudgets> originalBudgets() { return originalBudgets; }
    public String policyId() { return POLICY_ID; }
    public Origin origin() { return origin; }
    public Eligibility eligibility() { return eligibility; }
    /** Source facts only: no work, branch, route or edit permission. */
    public boolean productionEligible() { return eligibility == Eligibility.FACTUAL_DETACHED; }

    /** Revalidates the producer/row/normal association at a future engine boundary. */
    public void requireProfilesUnchanged(MultiScaleProfileSet profiles) {
        if (profiles != producedProfiles || profiles.levels().isEmpty()
                || profiles.levels().get(0).level() != 0 || profiles.levels().get(0).reduction() != 1) {
            throw new IllegalStateException("Optional-emission profiles do not belong to their retained producer");
        }
        for (var level : profiles.levels()) {
            if (level.profiles().size() != observations.size()) {
                throw new IllegalStateException("Optional-emission observation count changed");
            }
            for (int index = 0; index < observations.size(); index++) {
                var profile = level.profiles().get(index);
                var fact = observations.get(index);
                var normal = profile.normalScreen();
                if (!(profile.samplingAnchor() instanceof DetachedProfileSamplingLocation)
                        || !fact.location().equals(profile.samplingAnchor())
                        || Double.doubleToLongBits(normal.x) != Double.doubleToLongBits(fact.normal().x())
                        || Double.doubleToLongBits(normal.y) != Double.doubleToLongBits(fact.normal().y())) {
                    throw new IllegalStateException("Optional-emission coordinate or normal facts changed");
                }
            }
        }
    }
}
