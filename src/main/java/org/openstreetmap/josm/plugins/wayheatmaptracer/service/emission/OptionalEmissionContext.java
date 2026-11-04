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
    public enum Origin { DETACHED_FROZEN, DECLARED_SYNTHETIC_METRIC_GRID, MANAGED_CAPTURE }
    public enum Eligibility { FACTUAL_DETACHED, FACTUAL_MANAGED, DECLARED_SYNTHETIC, SYNTHETIC_SOURCE,
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
    private final OptionalEmissionContextFactory.ManagedCaptureBinding managedBinding;
    private final org.openstreetmap.josm.plugins.wayheatmaptracer.service.RetainedManagedScalarProfiles managed;

    OptionalEmissionContext(OptionalEmissionContextFactory.SourceBinding binding, RetainedScalarProfiles retained) {
        managedBinding = null;
        managed = null;
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

    OptionalEmissionContext(OptionalEmissionContextFactory.ManagedCaptureBinding binding,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.RetainedManagedScalarProfiles retained) {
        managedBinding = binding;
        managed = retained;
        nativeField = null;
        nativeGrid = retained.grid().view();
        producedProfiles = retained.profileSet();
        decisionRegion = null; evidenceRegion = null; rasterTransform = null;
        var ground = retained.groundTransform().orElseThrow();
        coordinateFrame = ground.frame();
        double pitch = ground.maximumNativePixelPitchMeters();
        resolution = EvidenceResolution.nativeSource(pitch,
                pitch / retained.sourceMosaic().virtualRasterScale());
        lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING,
                binding.detectorMode(), EvidenceCorrelationGroup.STRAVA_RENDERINGS, false);
        chainage = new ProfileChainage(retained.profiles().stream()
                .map(profile -> profile.cumulativeGroundDistanceMeters()).toList(),
                retained.sourceMosaic().parameters().sampleStepMeters());
        searchHalfWidthMeters = retained.sourceMosaic().parameters().halfWidthMeters();
        lateralStepMeters = retained.sourceMosaic().parameters().sampleStepMeters();
        nativePitchMeters = OptionalDouble.of(pitch);
        sourceIdentity = "managed-capture-" + binding.capture().selectionWitness().orElseThrow().selectedWayUniqueId();
        evidenceContentHash = managedGridHash(nativeGrid, binding.workBudget());
        requestIdentity = sourceIdentity;
        originalBudgets = Optional.empty();
        origin = Origin.MANAGED_CAPTURE;
        eligibility = Eligibility.FACTUAL_MANAGED;
        List<Observation> facts = new ArrayList<>(retained.profiles().size());
        for (int row = 0; row < retained.profiles().size(); row++) {
            binding.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var profile = retained.profiles().get(row);
            facts.add(new Observation(row, profile.samplingAnchor(), new RasterPoint(
                    profile.normalScreen().x, profile.normalScreen().y)));
        }
        observations = List.copyOf(facts);
        requireProfilesUnchanged(producedProfiles);
    }
    private static String managedGridHash(NativeScalarGridView grid, EmissionWorkBudget budget) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bits = new byte[8];
            for (int y = 0; y < grid.height(); y++) for (int x = 0; x < grid.width(); x++) {
                budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1);
                long value = Double.doubleToLongBits(grid.nodeValue(x, y));
                for (int i = 0; i < 8; i++) bits[i] = (byte) (value >>> (i * 8));
                digest.update(bits);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required scalar content digest unavailable", impossible);
        }
    }

    public ScalarIntensityField nativeField() {
        if (managed != null) throw new IllegalStateException("Managed exact-double grid has no float field");
        return nativeField;
    }
    public NativeScalarGridView nativeGrid() { return nativeGrid; }
    public List<Observation> observations() { return observations; }
    public MetricRegion decisionRegion() {
        if (managed != null) throw new IllegalStateException("Managed request authority is defined in captured world pixels");
        return decisionRegion;
    }
    public MetricRegion evidenceRegion() {
        if (managed != null) throw new IllegalStateException("Managed evidence authority is the acquired native grid");
        return evidenceRegion;
    }
    public RasterMetricTransform rasterTransform() {
        if (managed != null) throw new IllegalStateException("Managed ground transform is non-affine");
        return rasterTransform;
    }
    public LocalMetricFrame coordinateFrame() { return coordinateFrame; }
    public EvidenceResolution resolution() { return resolution; }
    public EvidenceFieldLineage lineage() { return lineage; }
    public ProfileChainage profileChainage() { return chainage; }
    /** Source-position measure; detached and declared-synthetic inputs retain native row S exactly. */
    public double sourceChainageMeters(int retainedRow) {
        if (managed != null) return managed.sourceParameters().orElseThrow().sourceChainageMeters(retainedRow);
        return observations.get(retainedRow).location().cumulativeGroundDistanceMeters();
    }
    /** Upper source-span admission; existing native-S inputs retain their exact historical difference. */
    public double sourceSpanUpperMeters(int fromRetainedRow, int toRetainedRow) {
        if (fromRetainedRow < 0 || toRetainedRow >= observations.size() || toRetainedRow < fromRetainedRow) {
            throw new IllegalArgumentException("Source span rows are invalid");
        }
        return managed == null ? sourceChainageMeters(toRetainedRow) - sourceChainageMeters(fromRetainedRow)
            : managed.sourceParameters().orElseThrow().sourceSpanUpperMeters(fromRetainedRow, toRetainedRow);
    }
    public double searchHalfWidthMeters() { return searchHalfWidthMeters; }
    public double lateralStepMeters() { return lateralStepMeters; }
    /** Maximum factual native pitch on the bound source interval; rendered fallback is never called native. */
    public OptionalDouble nativePitchMeters() { return nativePitchMeters; }
    public double outputRasterPitchMeters() { return resolution.outputRasterPitchMeters(); }
    /** Detached producer coordinates already use the L0 raster frame. */
    public double profileCoordinateScale() { return managed == null ? 1.0 : managed.sourceMosaic().virtualRasterScale(); }
    /** Maps original producer raster coordinates, including its normal/offset units. */
    public MetricPoint metricAt(RasterPoint point) {
        if (managed != null) return managed.groundTransform().orElseThrow().metricAt(point);
        return rasterTransform.pixelCenterToMetric(point.x(), point.y());
    }
    public RasterPoint rasterAt(MetricPoint point) {
        return managed == null ? rasterTransform.metricToPixelCenter(point)
            : managed.groundTransform().orElseThrow().rasterAt(point);
    }
    /** Factual source-pitch lookup in the source chainage measure. */
    public double nativePitchAtSourceChainage(double sourceMeters) {
        // A certified whole-domain upper bound remains valid at every source position.
        // It must not query native-S spatial samples using managed source S.
        if (managed != null) {
            if (!Double.isFinite(sourceMeters) || sourceMeters < 0
                    || sourceMeters > sourceChainageMeters(observations.size() - 1)) {
                throw new IllegalArgumentException("Managed source pitch query leaves its original source");
            }
            return managed.groundTransform().orElseThrow().maximumNativePixelPitchMeters();
        }
        return resolution.effectivePitchMetersAt(chainage.sourceOriginGroundMeters() + sourceMeters);
    }
    /** Local pitch at the actual connector probe; detached lookup retains its source-S formula. */
    public double nativePitchAt(RasterPoint actualProbe, double sourceMeters) {
        return managed == null ? nativePitchAtSourceChainage(sourceMeters)
            : managed.groundTransform().orElseThrow().nativePixelPitchAt(actualProbe);
    }
    public OptionalDouble minimumNativePitchMeters() {
        if (managed != null) return OptionalDouble.of(managed.groundTransform().orElseThrow()
                .minimumNativePixelPitchMeters());
        if (resolution.nativePitchMeters().isEmpty()) return OptionalDouble.empty();
        double minimum = resolution.nativePitchMeters().getAsDouble();
        for (var sample : resolution.spatialPitchSamples()) {
            if (sample.nativePitchMeters().isEmpty()) return OptionalDouble.empty();
            minimum = Math.min(minimum, sample.nativePitchMeters().getAsDouble());
        }
        return OptionalDouble.of(minimum);
    }
    /** Exact occurrence identity lookup in the managed producer's distinct source measure. */
    public double sourceChainageMeters(String occurrenceId) {
        if (managed == null) throw new IllegalStateException("This source has no managed occurrence certificate");
        for (var occurrence : managed.sourceParameters().orElseThrow().originalOccurrences()) {
            managedBinding.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (occurrence.occurrenceId().equals(occurrenceId)) return occurrence.sourceChainageMeters();
        }
        throw new IllegalArgumentException("Unknown original source occurrence");
    }
    public double outputPitchAt(MetricPoint point) {
        return managed == null ? resolution.outputRasterPitchMeters()
            : managed.groundTransform().orElseThrow().virtualPixelPitchAt(rasterAt(point));
    }
    /** Captured displacement of one original normal-offset unit; preserves detached affine arithmetic. */
    public double metricNormalPitchAtRow(int row) {
        var normal = observations.get(row).normal();
        if (managed != null) return managed.groundTransform().orElseThrow()
            .normalPitchAt(observations.get(row).location().rasterPoint(), normal);
        var transform = rasterTransform;
        double scale = transform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL
            ? transform.rasterPixelsPerSourcePixel() : 1;
        return Math.hypot((normal.x() * transform.xAxisEastMetersPerSourcePixel()
                + normal.y() * transform.yAxisEastMetersPerSourcePixel()) / scale,
            (normal.x() * transform.xAxisNorthMetersPerSourcePixel()
                + normal.y() * transform.yAxisNorthMetersPerSourcePixel()) / scale);
    }
    public double connectorProbeLengthUpper(RasterPoint from, RasterPoint to) {
        if (managed == null) return metricAt(from).distanceTo(metricAt(to));
        var a = nativeGrid.toGrid(from); var b = nativeGrid.toGrid(to);
        return Math.nextUp(Math.hypot(b.x() - a.x(), b.y() - a.y())
                * managed.groundTransform().orElseThrow().maximumNativePixelPitchMeters());
    }
    /** Unit metric transverse direction; managed transport uses its analytic local Jacobian. */
    public RasterPoint normalAt(RasterPoint probe, RasterPoint from, RasterPoint to) {
        if (managed != null) return managed.groundTransform().orElseThrow().transverseAt(probe, from, to);
        var a = metricAt(from); var b = metricAt(to);
        double dx = b.xMeters() - a.xMeters(), dy = b.yMeters() - a.yMeters();
        double length = Math.hypot(dx, dy);
        if (!(length > 0.0)) throw new IllegalArgumentException("Connector direction is zero");
        return new RasterPoint(-dy / length, dx / length);
    }
    public boolean routePositionAuthorized(MetricPoint point, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        if (managed != null) {
            RasterPoint raster = rasterAt(point);
            return routeSegmentAuthorized(raster, raster, budget, scratchOwner);
        }
        return decisionRegion.containsConservatively(point, CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units));
    }
    public boolean evidencePositionAuthorized(MetricPoint point, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        if (managed != null) return acquiredPosition(rasterAt(point), budget);
        return evidenceRegion.containsConservatively(point, CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units));
    }
    public boolean routeSegmentAuthorized(RasterPoint from, RasterPoint to, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        if (managed != null) return managedBinding.capture().originalRequest()
            .authorizesVirtualRasterSegment(managed.sourceMosaic(), from, to, budget, scratchOwner);
        return decisionRegion.containsSegmentWithConservativeEvents(metricAt(from), metricAt(to), CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units), scratchOwner);
    }
    public boolean evidenceSegmentAuthorized(RasterPoint from, RasterPoint to, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        // Publication requires every requested tile usable. The native rectangle is convex;
        // node/cell support is checked again by every actual bilinear read.
        if (managed != null) return acquiredPosition(from, budget) && acquiredPosition(to, budget);
        return evidenceRegion.containsSegmentWithConservativeEvents(metricAt(from), metricAt(to), CancellationProbe.NONE,
            units -> budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, units), scratchOwner);
    }
    private boolean acquiredPosition(RasterPoint point, EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 1);
        var p = nativeGrid.toGrid(point);
        if (p.x() < 0 || p.y() < 0 || p.x() > nativeGrid.width() - 1
                || p.y() > nativeGrid.height() - 1) return false;
        int x = Math.min(nativeGrid.width() - 2, (int) Math.floor(p.x()));
        int y = Math.min(nativeGrid.height() - 2, (int) Math.floor(p.y()));
        return nativeGrid.supportsCell(x, y);
    }
    public String sourceIdentity() { return sourceIdentity; }
    public String evidenceContentHash() { return evidenceContentHash; }
    public String requestIdentity() { return requestIdentity; }
    public Optional<TraceBudgets> originalBudgets() { return originalBudgets; }
    public String policyId() { return POLICY_ID; }
    public Origin origin() { return origin; }
    public Eligibility eligibility() { return eligibility; }
    /** Source facts only: no work, branch, route or edit permission. */
    public boolean productionEligible() {
        return eligibility == Eligibility.FACTUAL_DETACHED || eligibility == Eligibility.FACTUAL_MANAGED;
    }

    /** Revalidates the producer/row/normal association at a future engine boundary. */
    public void requireProfilesUnchanged(MultiScaleProfileSet profiles) {
        if (managedBinding != null) managedBinding.requireCurrent();
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
                if (!fact.location().equals(profile.samplingAnchor())
                        || Double.doubleToLongBits(normal.x) != Double.doubleToLongBits(fact.normal().x())
                        || Double.doubleToLongBits(normal.y) != Double.doubleToLongBits(fact.normal().y())) {
                    throw new IllegalStateException("Optional-emission coordinate or normal facts changed");
                }
            }
        }
    }
}
