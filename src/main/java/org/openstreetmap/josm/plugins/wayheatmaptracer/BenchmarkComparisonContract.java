package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterTransformCertificate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Maintainer-approved cross-version exceptions; native identities are never rewritten. */
public enum BenchmarkComparisonContract {
    STRICT("rc6-identical-v1"),
    RC10_APPROVED("rc10-approved-numerical-readonly-v1");

    // Exact reviewed live B parameter identities. A future policy needs a new approved contract.
    public static final String RC6_B_PARAMETERS = "df52ac49e7aae10c1b5953155ef4ba3cc9430c06b1b42ac8a9afe24f3235e825";
    public static final String RC10_B_PARAMETERS = "9814307c79fae2c8ec105c755c7373694d3c4e893be78a747062511361241b35";
    private final String identity;
    BenchmarkComparisonContract(String identity) { this.identity = identity; }
    public String identity() { return identity; }
    public static BenchmarkComparisonContract parse(String identity) {
        for (var value : values()) if (value.identity.equals(identity)) return value;
        throw new IllegalArgumentException("Unknown benchmark comparison contract");
    }

    public static Set<PrimitiveKey> frozenKeys(SelectedWayIntervalPartitioner.Partition partition) {
        if (partition == null) return Set.of();
        Set<PrimitiveKey> result = new HashSet<>();
        partition.fixedIslands().forEach(island -> result.addAll(island.occurrenceKeys()));
        return Set.copyOf(result);
    }

    public static void requireApprovedParameters(String baseline, String candidate) {
        if ((!RC6_B_PARAMETERS.equals(baseline) && !RC10_B_PARAMETERS.equals(baseline))
                || !RC10_B_PARAMETERS.equals(candidate)) {
            throw new IllegalStateException("Unapproved B numerical parameter identity");
        }
    }

    /** Compares factual shared payloads/watches and permits only read-only added context. */
    public static void requireReadOnlyExtension(NetworkSnapshot baseline, NetworkSnapshot candidate,
            Set<PrimitiveKey> fixedIslandKeys) {
        var a = baseline.closure(); var b = candidate.closure();
        if (!baseline.snapshotId().equals(candidate.snapshotId()) || baseline.role() != candidate.role()
                || baseline.sourceGeneration() != candidate.sourceGeneration()
                || a.scope() != b.scope() || !a.queryVersion().equals(b.queryVersion())
                || a.mayCreateNodes() != b.mayCreateNodes()
                || a.wayReferrersComplete() != b.wayReferrersComplete()
                || a.relationReferrersComplete() != b.relationReferrersComplete()
                || a.nearbyGeometryComplete() != b.nearbyGeometryComplete()
                || !a.collisionEnvelope().equals(b.collisionEnvelope())
                || !a.editRegion().equals(b.editRegion())
                || !a.editableWayOccurrences().equals(b.editableWayOccurrences())
                || !a.externalPorts().equals(b.externalPorts())
                || !b.primitiveKeys().containsAll(a.primitiveKeys())
                || !a.editableExistingKeys().containsAll(b.editableExistingKeys())
                || !a.movableExistingNodeKeys().containsAll(b.movableExistingNodeKeys())
                || !a.removableExistingNodeKeys().equals(b.removableExistingNodeKeys())
                || !b.protectedExistingNodeKeys().containsAll(a.protectedExistingNodeKeys())) {
            throw new IllegalStateException("Unapproved network authority or capture difference");
        }
        for (var key : a.primitiveKeys()) {
            if (!baseline.primitives().get(key).equals(candidate.primitives().get(key))
                    || !baseline.incomingReferrerWatches().get(key)
                            .equals(candidate.incomingReferrerWatches().get(key))) {
                throw new IllegalStateException("Shared original OSM payload or referrer watch differs");
            }
        }
        Set<PrimitiveKey> removedRights = new HashSet<>(a.editableExistingKeys());
        removedRights.removeAll(b.editableExistingKeys());
        Set<PrimitiveKey> removedMovement = new HashSet<>(a.movableExistingNodeKeys());
        removedMovement.removeAll(b.movableExistingNodeKeys()); removedRights.addAll(removedMovement);
        if (!fixedIslandKeys.containsAll(removedRights)
                || !b.protectedExistingNodeKeys().containsAll(removedRights)) {
            throw new IllegalStateException("Removed movement rights are not proved fixed islands");
        }
        Set<PrimitiveKey> addedProtection = new HashSet<>(b.protectedExistingNodeKeys());
        addedProtection.removeAll(a.protectedExistingNodeKeys());
        addedProtection.retainAll(a.primitiveKeys());
        if (!fixedIslandKeys.containsAll(addedProtection)) {
            throw new IllegalStateException("Unapproved protection change outside fixed islands");
        }
        for (var key : b.primitiveKeys()) if (!a.primitiveKeys().contains(key)) {
            if (key.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                    || b.editableExistingKeys().contains(key) || b.movableExistingNodeKeys().contains(key)
                    || b.removableExistingNodeKeys().contains(key)
                    || key.type() == PrimitiveKey.Type.NODE && !b.protectedExistingNodeKeys().contains(key)) {
                throw new IllegalStateException("Added context is not read-only");
            }
        }
    }

    public static void requireInputs(FrozenReplayInput baseline, FrozenReplayInput candidate,
            Set<PrimitiveKey> fixedIslandKeys) {
        requireReadOnlyExtension(baseline.network(), candidate.network(), fixedIslandKeys);
        BenchmarkDecisionComparatorMain.requireSameEvidence(baseline, candidate);
        requireRequests(baseline.request(), candidate.request());
    }

    /**
     * The bookmark pins source provenance; actual native publications pin effective settings/scalars.
     * The approved host calls this only after authenticating its pinned native baseline receipt/output.
     * The bounded fallback below is bookmark provenance, never native-grid equivalence or an engine tolerance.
     */
    public static void requireBookmarkProvenance(FrozenReplayInput bookmark, FrozenReplayInput nativeBaseline) {
        var n = bookmark.network(); var m = nativeBaseline.network();
        var a = bookmark.request(); var b = nativeBaseline.request();
        var x = bookmark.evidence(); var y = nativeBaseline.evidence();
        boolean exactGridMetadata = a.evidenceResolution().equals(b.evidenceResolution())
                && x.transform().equals(y.transform()) && x.resolution().equals(y.resolution());
        if (n.role() != m.role() || n.sourceGeneration() != m.sourceGeneration()
                || !n.closure().equals(m.closure()) || !n.primitives().equals(m.primitives())
                || !n.incomingReferrerWatches().equals(m.incomingReferrerWatches())
                || !a.selectedWayKey().equals(b.selectedWayKey()) || !a.selectedRange().equals(b.selectedRange())
                || a.engine() != b.engine() || a.geometryMode() != b.geometryMode()
                || !a.permissions().equals(b.permissions()) || !a.budgets().equals(b.budgets())
                || !a.parameterHash().equals(b.parameterHash()) || !a.samplerId().equals(b.samplerId())
                || Double.doubleToRawLongBits(a.configuredSampleStepMeters()) != Double.doubleToRawLongBits(b.configuredSampleStepMeters())
                || !a.profileChainage().equals(b.profileChainage())
                || !a.corridorInput().equals(b.corridorInput()) || !bookmark.options().equals(nativeBaseline.options())
                || !x.coordinateFrame().equals(y.coordinateFrame()) || !x.decisionRegion().equals(y.decisionRegion())
                || !x.evidenceRegion().equals(y.evidenceRegion()) || !x.resampling().equals(y.resampling())
                || !x.sourceIdentity().equals(y.sourceIdentity()) || !x.fields().keySet().equals(y.fields().keySet())
                || !exactGridMetadata && !boundedBookmarkGridMetadata(bookmark, nativeBaseline)) {
            throw new IllegalStateException("Native baseline does not belong to original bookmark source");
        }
        for (var name : x.fields().keySet()) {
            var p = x.fields().get(name); var q = y.fields().get(name);
            if (p.width() != q.width() || p.height() != q.height() || !p.lineage().equals(q.lineage())
                    || !java.util.Arrays.equals(p.copiedValidity(), q.copiedValidity())
                    || !java.util.Arrays.equals(p.copiedInterpolationValidity(), q.copiedInterpolationValidity())) {
                throw new IllegalStateException("Native baseline bookmark raster provenance differs");
            }
            double[] oldValues = p.copiedValues(), nativeValues = q.copiedValues();
            long changed = 0; double maximumDifference = 0;
            for (int i = 0; i < oldValues.length; i++) {
                if (Double.doubleToRawLongBits(oldValues[i]) != Double.doubleToRawLongBits(nativeValues[i])) {
                    changed++;
                    maximumDifference = Math.max(maximumDifference, Math.abs(oldValues[i] - nativeValues[i]));
                }
            }
            System.out.println("bookmarkScalarDifferenceCount=" + changed);
            System.out.println("bookmarkScalarMaximumAbsoluteDifference=" + maximumDifference);
        }
        if (!exactGridMetadata) {
            // The numerical cause is unproved. Report only the two common pitch identities.
            System.out.println("bookmarkCommonPitchBits=" + Long.toHexString(Double.doubleToRawLongBits(
                    x.resolution().nativePitchMeters().orElseThrow())));
            System.out.println("nativeBaselineCommonPitchBits=" + Long.toHexString(Double.doubleToRawLongBits(
                    y.resolution().nativePitchMeters().orElseThrow())));
        }
    }

    private static boolean boundedBookmarkGridMetadata(FrozenReplayInput bookmark, FrozenReplayInput nativeBaseline) {
        double a = commonBookmarkPitch(bookmark), b = commonBookmarkPitch(nativeBaseline);
        if (!Double.isFinite(a) || !Double.isFinite(b)
                || !bookmark.evidence().transform().origin().equals(nativeBaseline.evidence().transform().origin())
                || Math.abs(Double.doubleToRawLongBits(a) - Double.doubleToRawLongBits(b)) > 1L) return false;
        for (var field : bookmark.evidence().fields().values()) {
            long span = (long) field.width() + field.height();
            double bound = Math.nextUp(Math.abs(a - b) * span);
            if (!Double.isFinite(bound) || bound > 1e-9) return false;
        }
        return true;
    }

    /** Returns the single common pitch, or NaN when outside the narrowly named bookmark shape. */
    private static double commonBookmarkPitch(FrozenReplayInput input) {
        var r = input.evidence().resolution(); var t = input.evidence().transform();
        if (!input.request().evidenceResolution().equals(r) || r.kind() != EvidenceResolution.Kind.NATIVE_SOURCE
                || r.nativePitchMeters().isEmpty() || r.resampledPitchMeters().isEmpty()
                || r.spatialPitchSamples().size() != 1
                || !t.transformId().equals("metric-first-center-v1")
                || t.originKind() != RasterMetricTransform.OriginKind.METRIC_FIRST_PIXEL_CENTER
                || t.axisUnit() != RasterMetricTransform.AxisUnit.RASTER_PIXEL
                || !t.accuracyCertificate().equals(RasterTransformCertificate.exactMetricGrid())) return Double.NaN;
        double p = r.nativePitchMeters().getAsDouble();
        var sample = r.spatialPitchSamples().get(0);
        if (!Double.isFinite(p) || p <= 0.0 || sample.nativePitchMeters().isEmpty()
                || !sameBits(sample.chainageMeters(), 0.0)
                || !sameBits(r.renderedPitchMeters(), p) || !sameBits(r.resampledPitchMeters().getAsDouble(), p)
                || !sameBits(sample.nativePitchMeters().getAsDouble(), p) || !sameBits(sample.renderedPitchMeters(), p)
                || !sameBits(t.xAxisEastMetersPerSourcePixel(), p) || !sameBits(t.yAxisNorthMetersPerSourcePixel(), -p)
                || !sameBits(t.xAxisNorthMetersPerSourcePixel(), 0.0) || !sameBits(t.yAxisEastMetersPerSourcePixel(), 0.0)
                || !sameBits(t.rasterPixelsPerSourcePixel(), 1.0)) return Double.NaN;
        return p;
    }

    private static boolean sameBits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    public static void requireRequests(TraceRequest a, TraceRequest b) {
        requireApprovedParameters(a.parameterHash(), b.parameterHash());
        if (a.engine() != TrackerMode.PROBABILISTIC || b.engine() != TrackerMode.PROBABILISTIC
                || !a.selectedWayKey().equals(b.selectedWayKey()) || !a.selectedRange().equals(b.selectedRange())
                || a.geometryMode() != b.geometryMode() || !a.permissions().equals(b.permissions())
                || !a.budgets().equals(b.budgets()) || !a.settingsHash().equals(b.settingsHash())
                || !a.evidenceSnapshotId().equals(b.evidenceSnapshotId())
                || !a.evidenceContentHash().equals(b.evidenceContentHash())
                || !a.networkSnapshotId().equals(b.networkSnapshotId())
                || !a.samplerId().equals(b.samplerId())
                || Double.doubleToRawLongBits(a.configuredSampleStepMeters())
                    != Double.doubleToRawLongBits(b.configuredSampleStepMeters())
                || !a.profileChainage().equals(b.profileChainage())
                || !a.evidenceResolution().equals(b.evidenceResolution())
                || !a.corridorInput().equals(b.corridorInput())) {
            throw new IllegalStateException("Unapproved frozen request difference");
        }
    }

    /** Additional protection must survive in the actual immutable proposed output, bit for bit. */
    public static void requireFrozenPreserved(AlignmentEditPlan plan, Set<PrimitiveKey> frozen) {
        for (var key : frozen) if (!plan.before().primitives().get(key).equals(plan.after().primitives().get(key))) {
            throw new IllegalStateException("Frozen island primitive differs in proposed output");
        }
    }
}
