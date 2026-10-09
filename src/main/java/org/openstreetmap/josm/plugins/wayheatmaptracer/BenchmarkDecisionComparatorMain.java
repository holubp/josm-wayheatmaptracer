package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.BenchmarkIntervalDecisionComparison;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.BenchmarkFinalOutputComparison;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Cross-process typed admission of complete frozen decision inputs. */
public final class BenchmarkDecisionComparatorMain {
    private BenchmarkDecisionComparatorMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 4) {
            throw new IllegalArgumentException("Two production archives, optionally pinned original OSM and SHA256 required");
        }
        var firstArchive = Format15ArchiveReader.read(Path.of(args[0]));
        var secondArchive = Format15ArchiveReader.read(Path.of(args[1]));
        var firstArtifact = firstArchive.artifact("frozen-input.bin");
        var secondArtifact = secondArchive.artifact("frozen-input.bin");
        if (firstArtifact.isEmpty() != secondArtifact.isEmpty()) {
            throw new IllegalStateException("Single/interval production input kind differs");
        }
        if (firstArtifact.isEmpty()) {
            BenchmarkIntervalDecisionComparison.compare(firstArchive, secondArchive);
            System.out.println("typedDecisionInput=IDENTICAL_INTERVAL datasetIdentity=RUN_LOCAL");
            return;
        }
        FrozenReplayInput first = FrozenReplayCodec.decode(firstArtifact.orElseThrow().bytes());
        FrozenReplayInput second = FrozenReplayCodec.decode(secondArtifact.orElseThrow().bytes());
        requireSame(first, second);
        var outputs = BenchmarkFinalOutputComparison.compare(firstArchive, first, secondArchive, second);
        var firstPlan = firstArchive.artifact("frozen-edit-plan.bin");
        var secondPlan = secondArchive.artifact("frozen-edit-plan.bin");
        AlignmentEditPlan baselinePlan = firstPlan.map(value -> FrozenReplayCodec.decodeEditPlan(value.bytes())).orElse(null);
        AlignmentEditPlan candidatePlan = secondPlan.map(value -> FrozenReplayCodec.decodeEditPlan(value.bytes())).orElse(null);
        boolean proof = candidatePlan != null && (baselinePlan == null
                || candidatePlan.validation().findingCodes().contains("inherited-nontransport-contact-review-required")
                || !baselinePlan.validation().equals(candidatePlan.validation()));
        VerifiedOriginal original = null;
        if (proof) {
            if (args.length != 4) throw new IllegalStateException("Inherited plan admission requires pinned original OSM");
            original = verifyOriginal(Path.of(args[2]), args[3], second);
        }
        VerifiedOriginal bound = original;
        BenchmarkHostMain.onEventThread(() -> {
            requireCompatiblePlans(baselinePlan, candidatePlan, first, second,
                    outputs.candidate().selectedRoute(), bound == null ? null : bound.dataSet(),
                    bound == null ? Double.NaN : bound.halfWidthMeters(),
                    firstArchive.buildIdentity().equals(secondArchive.buildIdentity()));
            return null;
        });
        System.out.println("typedDecisionInput=IDENTICAL datasetIdentity=RUN_LOCAL");
        System.out.println("baselineNativeFrozenHash=" + first.canonicalHash());
        System.out.println("candidateNativeFrozenHash=" + second.canonicalHash());
    }

    public static void requireSame(FrozenReplayInput a, FrozenReplayInput b) {
        requireSameNetwork(a.network(), b.network());
        requireSameRequest(a.request(), b.request());
        if (!a.evidence().canonicalHash().equals(b.evidence().canonicalHash())
                || !a.options().equals(b.options())) {
            throw new IllegalStateException("Evidence frame or pipeline options differ");
        }
        Map<String, ScalarEvidenceField> fieldsA = a.evidence().fields();
        Map<String, ScalarEvidenceField> fieldsB = b.evidence().fields();
        if (!fieldsA.keySet().equals(fieldsB.keySet())) {
            throw new IllegalStateException("Scalar field inventory differs");
        }
        for (String name : fieldsA.keySet()) {
            ScalarEvidenceField p = fieldsA.get(name), q = fieldsB.get(name);
            if (p.width() != q.width() || p.height() != q.height()
                    || !p.lineage().equals(q.lineage())
                    || !Arrays.equals(p.copiedValidity(), q.copiedValidity())
                    || !Arrays.equals(p.copiedInterpolationValidity(), q.copiedInterpolationValidity())) {
                throw new IllegalStateException("Scalar support differs: " + name);
            }
            requireRawValuesEqual(p.copiedValues(), q.copiedValues(), name);
        }
    }

    public static void requireSameNetwork(NetworkSnapshot first, NetworkSnapshot second) {
        if (!first.snapshotId().equals(second.snapshotId()) || first.role() != second.role()
                || first.sourceGeneration() != second.sourceGeneration()
                || !first.closure().equals(second.closure())
                || !first.primitives().equals(second.primitives())
                || !first.incomingReferrerWatches().equals(second.incomingReferrerWatches())) {
            throw new IllegalStateException("Decision-bearing network closure differs");
        }
    }

    public static void requireSamePlan(AlignmentEditPlan x, AlignmentEditPlan y) {
        requireSamePlanState(x, y);
        if (!x.validation().equals(y.validation())) throw new IllegalStateException("Production edit plan differs");
    }

    private static void requireSamePlanState(AlignmentEditPlan x, AlignmentEditPlan y) {
        requireSameNetwork(x.before(), y.before());
        requireSameNetwork(x.after(), y.after());
        if (!x.selectedWayKey().equals(y.selectedWayKey())
                || !x.selectedRange().equals(y.selectedRange())
                || !x.metricFrame().equals(y.metricFrame())
                || !x.permissions().equals(y.permissions())
                || !x.settingsHash().equals(y.settingsHash())
                || !x.evidenceHash().equals(y.evidenceHash())
                || !x.parameterHash().equals(y.parameterHash())
                || !x.routeIdentity().equals(y.routeIdentity())
                || !x.finalPreviewWays().equals(y.finalPreviewWays())) {
            throw new IllegalStateException("Production edit plan differs");
        }
    }

    /** Exact counterpart admission, or independently certified historical missing-plan direction. */
    public static void requireCompatiblePlans(AlignmentEditPlan baseline, AlignmentEditPlan candidate,
            FrozenReplayInput first, FrozenReplayInput second, ModernTracePipeline.Route nativeRoute,
            DataSet originalOsm, double derivedHalfWidthMeters, boolean sameBuild) {
        requireSame(first, second);
        if (candidate == null) {
            if (baseline == null && sameBuild) return;
            throw new IllegalStateException("Candidate production edit plan is missing");
        }
        if (!candidate.before().equals(second.network())) {
            throw new IllegalStateException("Candidate plan differs from its own frozen input");
        }
        boolean inherited = candidate.validation().findingCodes()
                .contains("inherited-nontransport-contact-review-required");
        if (baseline != null) {
            requireSamePlanState(baseline, candidate);
            if (!inherited && baseline.validation().equals(candidate.validation())) return;
        } else if (first.network().semanticWitness() != null) {
            throw new IllegalStateException("Missing baseline plan is not historical witness absence");
        }
        if (originalOsm == null) throw new IllegalStateException("Original OSM semantic proof is required");
        NetworkSnapshotCapture.requireSemanticWitnessCurrent(originalOsm, candidate.before());
        var historical = ModernSingleWayEditPlanAdapter.requireFixedInheritedReviewPlan(candidate,
                second.request(), second.evidence(), second.options(), nativeRoute, derivedHalfWidthMeters);
        if (baseline != null && !baseline.validation().equals(candidate.validation())
                && !baseline.validation().equals(historical)) {
            throw new IllegalStateException("Plan validation differs beyond certified inherited contacts");
        }
    }

    private record VerifiedOriginal(DataSet dataSet, double halfWidthMeters) { }

    private static VerifiedOriginal verifyOriginal(Path osm, String expectedSha256,
            FrozenReplayInput candidate) throws Exception {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(osm)) throw new IllegalArgumentException("Pinned original OSM is required");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(osm), hash)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!expectedSha256.equals(HexFormat.of().formatHex(hash.digest()))) {
            throw new IllegalStateException("Original OSM SHA256 differs before parsing");
        }
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        DataSet dataSet;
        hash.reset();
        try (var stream = new DigestInputStream(Files.newInputStream(osm), hash)) {
            // OsmReader closes its supplied stream. Keep ownership until every input byte
            // (including trailing XML whitespace) has contributed to the verified pin.
            var parserStream = new java.io.FilterInputStream(stream) {
                @Override public void close() { }
            };
            dataSet = OsmReader.parseDataSet(parserStream, NullProgressMonitor.INSTANCE);
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!expectedSha256.equals(HexFormat.of().formatHex(hash.digest()))) {
            throw new IllegalStateException("Original OSM changed while parsing");
        }
        Way selected = (Way) dataSet.getPrimitiveById(candidate.request().selectedWayKey().id(), OsmPrimitiveType.WAY);
        if (selected == null) throw new IllegalStateException("Original selected way is missing");
        var range = candidate.request().selectedRange();
        var selection = new ArrayList<OsmPrimitive>();
        selection.add(selected);
        if (range.firstIndex() != 0 || range.lastIndex() != selected.getNodesCount() - 1) {
            selection.add(selected.getNode(range.firstIndex()));
            selection.add(selected.getNode(range.lastIndex()));
        }
        var fixture = BenchmarkHostMain.fixtureSource(candidate.network().sourceGeneration());
        PluginPreferences.save(fixture.config());
        fixture.saveCacheGenerationPreference();
        PluginPreferences.saveTracingSettings(new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES));
        PluginPreferences.saveGeometryCleanup(GeometryCleanupConfig.disabled());
        return BenchmarkHostMain.onEventThread(() -> {
            dataSet.setSelected(selection);
            var resolved = SelectionResolver.resolve(dataSet, false);
            if (resolved.startIndex() != range.firstIndex() || resolved.endIndex() != range.lastIndex()) {
                throw new IllegalStateException("Original selected occurrence range differs");
            }
            var seed = new LiveBPreviewService().captureManagedSeed(dataSet, resolved,
                    new AlignmentConfig(PluginPreferences.load(), GeometryCleanupConfig.disabled()), fixture.sourceIdentity());
            requireSameNetwork(candidate.network(), seed.network());
            if (!candidate.evidence().coordinateFrame().equals(seed.frame())
                    || !candidate.request().permissions().equals(seed.specification().permissions())
                    || !candidate.request().settingsHash().equals(seed.settingsHash())
                    || !candidate.request().parameterHash().equals(seed.parameterHash())) {
                throw new IllegalStateException("Original capture frame or effective configuration differs");
            }
            NetworkSnapshotCapture.requireSemanticWitnessCurrent(dataSet, candidate.network());
            return new VerifiedOriginal(dataSet, seed.searchRadiusMeters());
        });
    }

    static void requireRawValuesEqual(double[] first, double[] second, String name) {
        if (first.length != second.length) {
            throw new IllegalStateException("Scalar length differs: " + name);
        }
        for (int index = 0; index < first.length; index++) {
            if (Double.doubleToRawLongBits(first[index])
                    != Double.doubleToRawLongBits(second[index])) {
                throw new IllegalStateException("Scalar raw bits differ: " + name + " at " + index);
            }
        }
    }

    public static void requireSameRequest(TraceRequest x, TraceRequest y) {
        if (!x.selectedWayKey().equals(y.selectedWayKey())
                || !x.selectedRange().equals(y.selectedRange())
                || x.engine() != y.engine() || x.geometryMode() != y.geometryMode()
                || !x.permissions().equals(y.permissions()) || !x.budgets().equals(y.budgets())
                || !x.evidenceSnapshotId().equals(y.evidenceSnapshotId())
                || !x.evidenceContentHash().equals(y.evidenceContentHash())
                || !x.networkSnapshotId().equals(y.networkSnapshotId())
                || !x.settingsHash().equals(y.settingsHash())
                || !x.parameterHash().equals(y.parameterHash())
                || !x.samplerId().equals(y.samplerId())
                || Double.doubleToRawLongBits(x.configuredSampleStepMeters())
                    != Double.doubleToRawLongBits(y.configuredSampleStepMeters())
                || !x.profileChainage().equals(y.profileChainage())
                || !x.evidenceResolution().equals(y.evidenceResolution())
                || !x.corridorInput().equals(y.corridorInput())) {
            throw new IllegalStateException("Frozen request parameters differ");
        }
    }
}
