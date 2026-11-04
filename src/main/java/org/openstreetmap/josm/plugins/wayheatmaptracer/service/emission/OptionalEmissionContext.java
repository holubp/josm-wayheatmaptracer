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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RetainedScalarProfiles;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ScalarFieldGridView;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ScalarIntensityField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.NativeScalarGridView;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Immutable source facts; neither factual eligibility nor this context grants admitted execution. */
public final class OptionalEmissionContext {
    public static final String POLICY_ID = "optional-emission-native-integral-v2";
    public enum Origin { DETACHED_FROZEN, DECLARED_SYNTHETIC_METRIC_GRID }
    public enum Eligibility { FACTUAL_DETACHED, DECLARED_SYNTHETIC, SYNTHETIC_SOURCE,
        NATIVE_PITCH_UNAVAILABLE, FILTERED_SOURCE }
    /** Original observation identity within this source binding, with a frozen normal. */
    public record Observation(int index, ProfileSamplingLocation location, RasterPoint normal) { }

    private final ScalarIntensityField nativeField;
    private final NativeScalarGridView nativeGrid;
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
                binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION,
                    resolution.spatialPitchSamples().size());
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
        binding.budget().charge(EmissionWorkBudget.Operation.PREPARATION,
            Math.multiplyExact((long) observations.size(), producedProfiles.levels().size()));
        requireProfilesUnchanged(producedProfiles);
    }

    public ScalarIntensityField nativeField() { return nativeField; }
    public NativeScalarGridView nativeGrid() { return nativeGrid; }
    public List<Observation> observations() { return observations; }
    public MetricRegion decisionRegion() { return decisionRegion; }
    public MetricRegion evidenceRegion() { return evidenceRegion; }
    public RasterMetricTransform rasterTransform() { return rasterTransform; }
    public LocalMetricFrame coordinateFrame() { return coordinateFrame; }
    public EvidenceResolution resolution() { return resolution; }
    public EvidenceFieldLineage lineage() { return lineage; }
    public ProfileChainage profileChainage() { return chainage; }
    /** Source-position measure; detached and declared-synthetic inputs retain native row S exactly. */
    public double sourceChainageMeters(int retainedRow) {
        return observations.get(retainedRow).location().cumulativeGroundDistanceMeters();
    }
    /** Upper source-span admission; existing native-S inputs retain their exact historical difference. */
    public double sourceSpanUpperMeters(int fromRetainedRow, int toRetainedRow) {
        if (fromRetainedRow < 0 || toRetainedRow >= observations.size() || toRetainedRow < fromRetainedRow) {
            throw new IllegalArgumentException("Source span rows are invalid");
        }
        return sourceChainageMeters(toRetainedRow) - sourceChainageMeters(fromRetainedRow);
    }
    public double searchHalfWidthMeters() { return searchHalfWidthMeters; }
    public double lateralStepMeters() { return lateralStepMeters; }
    /** Maximum factual native pitch on the bound source interval; rendered fallback is never called native. */
    public OptionalDouble nativePitchMeters() { return nativePitchMeters; }
    public double outputRasterPitchMeters() { return resolution.outputRasterPitchMeters(); }
    /** Detached producer coordinates already use the L0 raster frame. */
    public double profileCoordinateScale() { return 1.0; }
    /** Maps original producer raster coordinates, including its normal/offset units. */
    public MetricPoint metricAt(RasterPoint point) {
        return rasterTransform.pixelCenterToMetric(point.x(), point.y());
    }
    public RasterPoint rasterAt(MetricPoint point) { return rasterTransform.metricToPixelCenter(point); }
    /** Factual source-pitch lookup in the source chainage measure. */
    public double nativePitchAtSourceChainage(double sourceMeters) {
        return resolution.effectivePitchMetersAt(chainage.sourceOriginGroundMeters() + sourceMeters);
    }
    public double outputPitchAt(MetricPoint point) { return resolution.outputRasterPitchMeters(); }
    /** Captured displacement of one original normal-offset unit; preserves detached affine arithmetic. */
    public double metricNormalPitchAtRow(int row) {
        var normal = observations.get(row).normal();
        var transform = rasterTransform;
        double scale = transform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL
            ? transform.rasterPixelsPerSourcePixel() : 1;
        return Math.hypot((normal.x() * transform.xAxisEastMetersPerSourcePixel()
                + normal.y() * transform.yAxisEastMetersPerSourcePixel()) / scale,
            (normal.x() * transform.xAxisNorthMetersPerSourcePixel()
                + normal.y() * transform.yAxisNorthMetersPerSourcePixel()) / scale);
    }
    public boolean routePositionAuthorized(MetricPoint point, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        return decisionRegion.containsConservatively(point, CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units));
    }
    public boolean evidencePositionAuthorized(MetricPoint point, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        return evidenceRegion.containsConservatively(point, CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units));
    }
    public boolean routeSegmentAuthorized(RasterPoint from, RasterPoint to, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        return decisionRegion.containsSegment(metricAt(from), metricAt(to), CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units), scratchOwner);
    }
    public boolean evidenceSegmentAuthorized(RasterPoint from, RasterPoint to, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        return evidenceRegion.containsSegment(metricAt(from), metricAt(to), CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units), scratchOwner);
    }
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
