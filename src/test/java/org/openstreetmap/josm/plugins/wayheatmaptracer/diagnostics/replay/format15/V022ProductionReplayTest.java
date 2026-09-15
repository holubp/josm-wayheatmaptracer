package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Production-path replay regressions: frozen values must reach real modern engines. */
class V022ProductionReplayTest {
    @Test
    void strictCliTraversesHashedNestedCorpusWithPathsContainingSpaces(
            @TempDir Path directory) throws Exception {
        String fineResults = runStrictCli(directory.resolve("fine fixture with spaces"),
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE, RasterFixture.FINE),
            "A,B,HYBRID", 51.0, 55.0);
        assertEquals(3, occurrences(fineResults, "\"status\":\"ok\""));

        String coarseImageResults = runStrictCli(directory.resolve("coarse image fixture"),
            fixture(TrackerMode.DIRECTIONAL_IMAGE, Scene.RIDGE, RasterFixture.COARSE),
            "IMAGE", 53.0, 57.0);
        assertEquals(1, occurrences(coarseImageResults, "\"status\":\"ok\""));
        assertFalse((fineResults + coarseImageResults).contains(directory.toString()));
    }

    @Test
    void frozenInputRoundTripsAndRunsEveryModernEngine(@TempDir Path directory) throws Exception {
        FrozenReplayInput captured = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        FrozenReplayInput decoded = FrozenReplayCodec.decode(FrozenReplayCodec.encode(captured));
        assertEquals(captured.evidence().canonicalHash(), decoded.evidence().canonicalHash());
        assertEquals(captured.network().canonicalHash(), decoded.network().canonicalHash());
        assertEquals(captured.request().corridorInput(), decoded.request().corridorInput());
        Path archivePath = directory.resolve("analytic ridge.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test", decoded), archivePath);
        Format15Archive archive = Format15ArchiveReader.read(archivePath);

        for (TrackerMode engine : List.of(TrackerMode.CORRIDOR_AWARE,
                TrackerMode.PROBABILISTIC, TrackerMode.HYBRID)) {
            assertProductionReplay(archive, decoded, engine, 51.0, 55.0);
        }

        FrozenReplayInput image = fixture(TrackerMode.DIRECTIONAL_IMAGE, Scene.RIDGE,
            RasterFixture.COARSE);
        Path imagePath = directory.resolve("coarse image ridge.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test", image), imagePath);
        assertProductionReplay(Format15ArchiveReader.read(imagePath), image,
            TrackerMode.DIRECTIONAL_IMAGE, 53.0, 57.0);
    }

    @Test
    void ordinaryFinePitchLongRoutesRejectTruncatedBAndImageAlternatives() {
        for (TrackerMode engine : List.of(TrackerMode.PROBABILISTIC,
                TrackerMode.DIRECTIONAL_IMAGE)) {
            FrozenReplayInput input = fixture(engine, Scene.RIDGE,
                RasterFixture.FINE_DENSE);
            Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
                ReplayLevel.SCALAR_INFERENCE, engine);

            assertFalse(actual.inference().hypotheses().isEmpty());
            assertTrue(actual.inference().alternativesTruncated());
            assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,
                actual.inference().status());
            assertTrue(actual.inference().evaluatedStates() > 0);
            assertTrue(actual.inference().evaluatedTransitions() > 0);
            assertThrows(ReplayMismatchException.class,
                () -> ProductionReplayValidator.validateScalar(actual));
        }
    }

    @Test
    void uniformNoSignalCannotSatisfyAProductionReplayGate() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.NO_SIGNAL);
        for (TrackerMode engine : List.of(TrackerMode.CORRIDOR_AWARE,
                TrackerMode.PROBABILISTIC, TrackerMode.HYBRID,
                TrackerMode.DIRECTIONAL_IMAGE)) {
            assertThrows(ReplayMismatchException.class, () -> {
                Format15ReplayRunner.Result result = Format15ReplayRunner.replay(
                    input, ReplayLevel.SCALAR_INFERENCE, engine);
                ProductionReplayValidator.validateScalar(result);
            }, engine + " must not satisfy the strict no-signal gate");
        }
    }

    @Test
    void weakerParallelRidgeDoesNotReplaceTheSelectedAnalyticRidge() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.PARALLEL);
        for (TrackerMode engine : List.of(TrackerMode.CORRIDOR_AWARE, TrackerMode.PROBABILISTIC,
                TrackerMode.HYBRID, TrackerMode.DIRECTIONAL_IMAGE)) {
            Format15ReplayRunner.Result result = Format15ReplayRunner.replay(
                input, ReplayLevel.FINAL_GEOMETRY, engine);
            assertFalse(result.geometry().isEmpty());
            assertTrue(interiorMeanY(result.geometry().get(0).points()) > 52.0
                    && interiorMeanY(result.geometry().get(0).points()) < 58.0,
                engine + " switched to the weaker parallel ridge");
        }
    }

    @Test
    void codecRejectsAggregateCollectionsBeforeSerializingThem() {
        FrozenReplayInput base = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        int width = 500;
        int height = 300;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(values, 0.1);
        Arrays.fill(valid, true);
        ScalarEvidenceField first = new ScalarEvidenceField(width, height, values, valid,
            base.evidence().fields().get("native").lineage());
        ScalarEvidenceField second = new ScalarEvidenceField(width, height, values, valid,
            base.evidence().fields().get("native").lineage());
        EvidenceSnapshot evidence = new EvidenceSnapshot("large-evidence",
            base.evidence().coordinateFrame(),
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1),
            base.evidence().resolution(), MetricRegion.rectangle(1, 1, 160, 100),
            MetricRegion.rectangle(-0.5, -0.5, 499.5, 299.5),
            Map.of("one", first, "two", second), "bounded-source");
        FrozenReplayInput large = withEvidence(base, evidence);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.encode(large));
        assertTrue(allMessages(failure).contains("aggregate"));
    }

    @Test
    void codecRejectsAggregateScalarOperationsBeforeSerializingThem() {
        FrozenReplayInput base = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        List<String> operations = Collections.nCopies(250_000, "synthetic-operation");
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false, operations);
        ScalarEvidenceField field = base.evidence().fields().get("native");
        ScalarEvidenceField shared = new ScalarEvidenceField(field.width(), field.height(),
            field.copiedValues(), field.copiedValidity(),
            field.copiedInterpolationValidity(), lineage);
        EvidenceSnapshot evidence = new EvidenceSnapshot("large-lineage",
            base.evidence().coordinateFrame(), base.evidence().transform(),
            base.evidence().resolution(), base.evidence().decisionRegion(),
            base.evidence().evidenceRegion(), Map.of("one", shared, "two", shared,
                "three", shared), base.evidence().resampling(), "bounded-source");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.encode(withEvidence(base, evidence)));
        assertTrue(allMessages(failure).contains("aggregate collection"));
    }

    @Test
    void codecRejectsUnknownVersionTrailingDataAndNonfiniteScalar() {
        byte[] encoded = FrozenReplayCodec.encode(
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE));

        byte[] unknownVersion = encoded.clone();
        unknownVersion[7] = (byte) (FrozenReplayCodec.VERSION + 1);
        assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(unknownVersion));

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(trailing));

        byte[] nonfinite = encoded.clone();
        byte[] finite = ByteBuffer.allocate(Double.BYTES).putDouble(0.02).array();
        int offset = firstOccurrence(nonfinite, finite);
        assertTrue(offset >= 0);
        ByteBuffer.wrap(nonfinite, offset, Double.BYTES).putDouble(Double.NaN);
        assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(nonfinite));
    }

    @Test
    void codecRejectsMalformedUtf8AndPrivatePathMetadata() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        byte[] bytes = FrozenReplayCodec.encode(input);
        bytes[12] = (byte) 0xc3;
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(bytes));
        assertTrue(allMessages(malformed).toLowerCase().contains("utf-8"));

        EvidenceSnapshot privateEvidence = new EvidenceSnapshot("private-evidence",
            input.evidence().coordinateFrame(), input.evidence().transform(),
            input.evidence().resolution(), input.evidence().decisionRegion(),
            input.evidence().evidenceRegion(), input.evidence().fields(),
            input.evidence().resampling(), "/private/corpus/archive.zip");
        FrozenReplayInput privateInput = withEvidence(input, privateEvidence);
        IllegalArgumentException privatePath = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.encode(privateInput));
        assertTrue(allMessages(privatePath).contains("private path"));
    }

    @Test
    void productionWriterRejectsWindowsDriveIdentityBeforeCreatingArchive(
            @TempDir Path directory) {
        assertBuildIdentityRejectedBeforeWrite("C:\\synthetic\\private\\archive.zip",
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE), directory.resolve("drive.zip"));
    }

    @Test
    void productionWriterRejectsUncIdentityBeforeCreatingArchive(@TempDir Path directory) {
        assertBuildIdentityRejectedBeforeWrite("\\\\synthetic-server\\private\\archive.zip",
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE), directory.resolve("unc.zip"));
    }

    @Test
    void productionBuildIdentityHasPlatformIndependentRootBoundaries(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        List<String> rejected = List.of("/synthetic/private/archive.zip",
            "C:/synthetic/private/archive.zip", "\\synthetic\\rooted.zip",
            "\\\\?\\C:\\synthetic\\device.zip", "file:///synthetic/archive.zip",
            "build?X-Amz-Signature=synthetic");
        for (int index = 0; index < rejected.size(); index++) {
            assertBuildIdentityRejectedBeforeWrite(rejected.get(index), input,
                directory.resolve("rejected-" + index + ".zip"));
        }

        Path safe = directory.resolve("safe.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create(
            "build-2026.09.15+synthetic", input), safe);
        assertTrue(Files.isRegularFile(safe));
    }

    @Test
    void productionBundleRejectsAbsoluteAndSignedBuildIdentityBeforePersistence() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Format15ProductionBundleFactory.create(
                "/private/archive.zip?X-Amz-Signature=synthetic", input));
        assertTrue(allMessages(failure).contains("private path or signed value"));
    }

    @Test
    void strictCliTreatsCorpusInventoryErrorsAsTypedFailures(@TempDir Path directory)
            throws Exception {
        CliRun run = runCli(directory, fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE),
            "A", 51.0, 55.0, false, "{}", "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]",
            "[{\"source\":\"broken.zip#0123456789ab\",\"code\":\"MALFORMED_ZIP\"}]");

        assertEquals(2, run.exit());
        assertTrue(run.output().contains("\"inventoryErrors\":1"));
        assertTrue(run.output().contains("\"code\":\"MALFORMED_ZIP\""));
        assertEquals(1, occurrences(run.output(), "\"status\":\"ok\""));
    }

    @Test
    void officialPythonInventoryFeedsStrictProductionReplay(@TempDir Path directory)
            throws Exception {
        Path corpus = directory.resolve("official corpus with spaces");
        Files.createDirectories(corpus);
        Path inner = directory.resolve("factory bundle.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test-build",
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE)), inner);
        byte[] innerBytes = Files.readAllBytes(inner);
        Path outer = corpus.resolve("outer archive with spaces.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outer))) {
            zip.putNextEntry(new ZipEntry("nested/factory bundle.zip"));
            zip.write(innerBytes);
            zip.closeEntry();
        }
        Path manifest = directory.resolve("official manifest with spaces.json");
        Process inventory = new ProcessBuilder("python3", "scripts/v022-corpus.py", "inventory",
            "--inputs", corpus.toString(), "--output", manifest.toString())
            .directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).start();
        String inventoryOutput = new String(inventory.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertEquals(0, inventory.waitFor(), inventoryOutput);
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
        assertEquals(1, occurrences(manifestText, "\"caseId\""));
        assertTrue(manifestText.contains("\"errors\": []"));

        Path output = directory.resolve("strict replay results.json");
        int exit = ProductionReplayCommand.run(new String[] {"--manifest", manifest.toString(),
            "--engines", "A", "--output", output.toString(), "--strict", "--offline",
            "--ablation-config", "{}"});
        assertEquals(0, exit, Files.readString(output, StandardCharsets.UTF_8));
        assertTrue(Files.readString(output, StandardCharsets.UTF_8)
            .contains("\"status\":\"ok\""));
    }

    @Test
    void strictCliRetainsEveryRequestedHashFailureAndRejectsWrongGeometry(
            @TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        CliRun hashFailure = runCli(directory.resolve("bad outer hash"), input,
            "A,B,HYBRID", 51.0, 55.0, true, "{}");
        assertEquals(2, hashFailure.exit());
        assertEquals(3, occurrences(hashFailure.output(), "\"status\":\"failed\""));
        assertEquals(3, occurrences(hashFailure.output(),
            "\"reason\":\"outer-sha256-mismatch\""));
        assertFalse(hashFailure.output().contains(directory.toString()));

        CliRun wrongGeometry = runCli(directory.resolve("wrong invariant"), input,
            "A", 80.0, 90.0, false, "{}");
        assertEquals(2, wrongGeometry.exit());
        assertTrue(wrongGeometry.output().contains(
            "\"reason\":\"interior-route-invariant-failed\""));
    }

    @Test
    void unsupportedAblationOptionFailsInsteadOfBeingIgnored(@TempDir Path directory) {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> runCli(directory, input, "A", 51.0, 55.0, false,
                "{\"bypassProductionEngine\":true}"));
        assertEquals("unsupported-ablation-option", failure.getMessage());
    }

    @Test
    void strictCliReportsUnsupportedAndMissingCapabilitiesPerEngine(
            @TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        CliRun unsupported = runCli(directory.resolve("unsupported raster"), input,
            "A,IMAGE", 0.0, 0.0, false, "{}", "RASTER_INFERENCE", "[]");
        assertEquals(2, unsupported.exit());
        assertEquals(2, occurrences(unsupported.output(),
            "\"reason\":\"unsupported-replay-level\""));

        CliRun missing = runCli(directory.resolve("missing input"), input,
            "A,B", 0.0, 0.0, false, "{}", "FINAL_GEOMETRY",
            "[\"frozenRaster\"]");
        assertEquals(2, missing.exit());
        assertEquals(2, occurrences(missing.output(),
            "\"reason\":\"manifest-inputs-missing\""));

        CliRun scalar = runCli(directory.resolve("scalar only"), input,
            "A", 80.0, 90.0, false, "{}", "SCALAR_INFERENCE", "[]");
        assertEquals(0, scalar.exit(), scalar.output());
        assertTrue(scalar.output().contains(
            "\"replayLevel\":\"SCALAR_INFERENCE\""));

        CliRun noRoute = runCli(directory.resolve("faithful no route"),
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.NO_SIGNAL),
            "A", 0.0, 0.0, false, "{}", "SCALAR_INFERENCE", "[]");
        assertEquals(2, noRoute.exit());
        assertTrue(noRoute.output().contains("\"actualStatus\":\"NO_ROUTE\""));
        assertTrue(noRoute.output().contains("\"alternativesTruncated\":false"));
    }

    @Test
    void rasterAndEditReplayRemainExplicitlyUnsupported(@TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.PROBABILISTIC, Scene.RIDGE);
        Path archivePath = directory.resolve("input.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test", input), archivePath);
        Format15Archive archive = Format15ArchiveReader.read(archivePath);
        assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replay(archive,
            ReplayLevel.RASTER_INFERENCE, input.canonicalHash(),
            input.request().parameterHash()));
        assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replay(archive,
            ReplayLevel.FULL_EDIT_PLAN, input.canonicalHash(),
            input.request().parameterHash()));
    }

    private static void assertBuildIdentityRejectedBeforeWrite(String identity,
            FrozenReplayInput input, Path output) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Format15BundleWriter.write(
                Format15ProductionBundleFactory.create(identity, input), output), identity);
        assertTrue(allMessages(failure).contains("private path or signed value"), identity);
        assertFalse(Files.exists(output), identity);
    }

    private static String runStrictCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY) throws Exception {
        CliRun run = runCli(directory, input, engines, minimumY, maximumY,
            false, "{}");
        assertEquals(0, run.exit(), run.output());
        return run.output();
    }

    private static CliRun runCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY,
            boolean corruptOuterHash, String ablationContents) throws Exception {
        return runCli(directory, input, engines, minimumY, maximumY,
            corruptOuterHash, ablationContents, "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
    }

    private static CliRun runCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY,
            boolean corruptOuterHash, String ablationContents,
            String capability, String missingInputs) throws Exception {
        return runCli(directory, input, engines, minimumY, maximumY,
            corruptOuterHash, ablationContents, capability, missingInputs, "[]");
    }

    private static CliRun runCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY,
            boolean corruptOuterHash, String ablationContents,
            String capability, String missingInputs, String errors) throws Exception {
        Files.createDirectories(directory);
        Path inner = directory.resolve("bundle source.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test", input), inner);
        byte[] innerBytes = Files.readAllBytes(inner);
        Path outer = directory.resolve("outer archive with spaces.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outer))) {
            zip.putNextEntry(new ZipEntry("nested/bundle one.zip"));
            zip.write(innerBytes);
            zip.closeEntry();
        }
        String outerHash = Format15Safety.sha256(Files.readAllBytes(outer));
        if (corruptOuterHash) {
            outerHash = "0".repeat(64);
        }
        String bundleName = outer.getFileName() + "!nested/bundle one.zip";
        String manifest = "{\"schema\":\"wayheatmaptracer-v022-corpus-1\","
            + "\"inputRoot\":\"" + json(directory.toString()) + "\","
            + "\"cases\":[{\"caseId\":\"case-001\","
            + "\"sourcePath\":\"" + json(outer.toString()) + "\","
            + "\"outerSha256\":\"" + outerHash + "\","
            + "\"bundleName\":\"" + json(bundleName) + "\","
            + "\"bundleSha256\":\"" + Format15Safety.sha256(innerBytes) + "\","
            + "\"byteSize\":" + innerBytes.length + ","
            + "\"replayCapability\":" + jsonQuote(capability) + ","
            + "\"missingInputs\":" + missingInputs + ","
            + "\"expectedRoute\":{\"minimumLengthMeters\":119.0,"
            + "\"minimumDirectSupportMeters\":50.0,"
            + "\"interiorMeanYMin\":" + minimumY
            + ",\"interiorMeanYMax\":" + maximumY + "}}],\"errors\":" + errors + "}";
        Path manifestPath = directory.resolve("corpus manifest.json");
        Path output = directory.resolve("results folder/replay results.json");
        Path ablation = directory.resolve("ablation config.json");
        Files.writeString(manifestPath, manifest, StandardCharsets.UTF_8);
        Files.writeString(ablation, ablationContents, StandardCharsets.UTF_8);

        int exit = ProductionReplayCommand.run(new String[] {"--manifest",
            manifestPath.toString(), "--engines", engines, "--output", output.toString(),
            "--strict", "--offline", "--ablation-config", ablation.toString()});
        return new CliRun(exit, Files.readString(output, StandardCharsets.UTF_8));
    }

    private static void assertProductionReplay(Format15Archive archive,
            FrozenReplayInput input, TrackerMode engine, double minimumY,
            double maximumY) {
        Format15ReplayRunner.Result scalar = Format15ReplayRunner.replay(archive,
            ReplayLevel.SCALAR_INFERENCE, input.canonicalHash(),
            input.request().parameterHash(), engine);
        assertEquals(engine, scalar.engine());
        assertEquals(input.canonicalHash(), scalar.inputHash());
        assertFalse(scalar.inference().hypotheses().isEmpty(),
            engine + " must emit an actual ridge hypothesis");
        assertFalse(scalar.inference().alternativesTruncated(),
            engine + " analytic route must not exhaust alternatives");
        assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, scalar.inference().status());
        assertTrue(scalar.inference().evaluatedStates() > 0,
            engine + " must expose actual solver state work");
        assertTrue(scalar.inference().evaluatedTransitions() > 0,
            engine + " must expose actual solver transition work");

        Format15ReplayRunner.Result finalResult = Format15ReplayRunner.replay(archive,
            ReplayLevel.FINAL_GEOMETRY, input.canonicalHash(),
            input.request().parameterHash(), engine);
        assertEquals(engine, finalResult.engine());
        assertFalse(finalResult.geometry().isEmpty(),
            engine + " final replay must evaluate actual geometry");
        var route = finalResult.routes().get(0);
        var points = route.hypothesis().points();
        assertEquals(20.0, points.get(0).xMeters(), 0.05);
        assertEquals(50.0, points.get(0).yMeters(), 0.05);
        assertEquals(140.0, points.get(points.size() - 1).xMeters(), 0.05);
        assertEquals(50.0, points.get(points.size() - 1).yMeters(), 0.05);
        assertTrue(polylineLength(points) > 119.0,
            engine + " route must span the physical selection");
        assertTrue(interiorMeanY(points) > minimumY && interiorMeanY(points) < maximumY,
            engine + " must follow the displaced analytic ridge");
        assertTrue(route.quality().directlySupportedLengthMeters() > 50.0,
            engine + " must retain measured physical support");
    }

    private static FrozenReplayInput fixture(TrackerMode engine, Scene scene) {
        return fixture(engine, scene, RasterFixture.FINE);
    }

    private static FrozenReplayInput fixture(TrackerMode engine, Scene scene,
            RasterFixture raster) {
        GeographicPoint origin = new GeographicPoint(50, 14);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(first, new DetachedNode(first,
            frame.toGeographic(new MetricPoint(20, 50)), Map.of(), false, false));
        values.put(last, new DetachedNode(last,
            frame.toGeographic(new MetricPoint(140, 50)), Map.of(), false, false));
        values.put(way, new DetachedWay(way, List.of(first, last),
            Map.of("highway", "path", "note", "/survey/reference"), false, false));
        MetricRegion region = MetricRegion.rectangle(1, 1, 159, 99);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
            "replay-test-v1", values.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
            Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
            false, true, true, true);
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
        watches.put(first, Set.of(way));
        watches.put(last, Set.of(way));
        watches.put(way, Set.of());
        NetworkSnapshot network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE,
            "dataset", 1, closure, values, watches);

        int width = raster == RasterFixture.COARSE ? 33 : 161;
        int height = raster == RasterFixture.COARSE ? 21 : 101;
        double[] intensity = new double[width * height];
        boolean[] valid = new boolean[intensity.length];
        Arrays.fill(intensity, 0.02);
        Arrays.fill(valid, true);
        if (scene != Scene.NO_SIGNAL) {
            int center = raster == RasterFixture.COARSE ? 11 : 53;
            Arrays.fill(intensity, (center - 2) * width, (center - 1) * width, 0.25);
            Arrays.fill(intensity, (center - 1) * width, center * width, 0.65);
            Arrays.fill(intensity, center * width, (center + 1) * width, 1.0);
            Arrays.fill(intensity, (center + 1) * width, (center + 2) * width, 0.65);
            Arrays.fill(intensity, (center + 2) * width, (center + 3) * width, 0.25);
        }
        if (scene == Scene.PARALLEL) {
            int parallel = raster == RasterFixture.COARSE ? 9 : 60;
            Arrays.fill(intensity, parallel * width, (parallel + 1) * width, 0.45);
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, intensity, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        double rasterPitch = raster == RasterFixture.COARSE ? 5.0 : 1.0;
        EvidenceResolution resolution = raster != RasterFixture.COARSE
            ? EvidenceResolution.nativeSource(2, 1).resampledTo(1)
            : EvidenceResolution.nativeSource(10, 5).resampledTo(5);
        EvidenceSnapshot evidence = new EvidenceSnapshot("evidence", frame,
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), rasterPitch, 0, 0,
                rasterPitch), resolution,
            region, MetricRegion.rectangle(-0.5 * rasterPitch, -0.5 * rasterPitch,
                160 + 0.5 * rasterPitch, 100 + 0.5 * rasterPitch),
            Map.of("native", field), "source");
        List<Double> chainage = raster == RasterFixture.FINE_DENSE
            ? List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0)
            : List.of(0.0, 60.0, 120.0);
        List<DetachedProfileSamplingLocation> locations = chainage.stream().map(distance ->
            DetachedProfileSamplingLocation.at(
                frame.toGeographic(new MetricPoint(20 + distance, 50)),
                frame, evidence.transform(), distance)).toList();
        Optional<CorridorTraceInput> corridor = Optional.of(
            new CorridorTraceInput(locations, 1.0));
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), engine,
            AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(7),
            new TraceBudgets(96, 8_000_000L, 128_000_000L, 32, 32),
            evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(),
            network.canonicalHash(), "settings",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sampler", chainage.size() == 3 ? 60 : 20,
            new ProfileChainage(chainage, chainage.size() == 3 ? 60 : 20),
            resolution, corridor);
        return new FrozenReplayInput(request, evidence, network,
            new ModernTracePipeline.Options("native", GeometryCleanupConfig.disabled(),
                "synthetic", 0));
    }

    private static FrozenReplayInput withEvidence(FrozenReplayInput base,
            EvidenceSnapshot evidence) {
        TraceRequest source = base.request();
        TraceRequest request = new TraceRequest(source.selectedWayKey(), source.selectedRange(),
            source.engine(), source.geometryMode(), source.permissions(), source.budgets(),
            evidence.snapshotId(), evidence.canonicalHash(), source.networkSnapshotId(),
            source.networkContentHash(), source.settingsHash(), source.parameterHash(),
            source.samplerId(), source.configuredSampleStepMeters(), source.profileChainage(),
            evidence.resolution(), source.corridorInput());
        return new FrozenReplayInput(request, evidence, base.network(), base.options());
    }

    private static double polylineLength(List<MetricPoint> points) {
        double length = 0;
        for (int index = 1; index < points.size(); index++) {
            length += points.get(index - 1).distanceTo(points.get(index));
        }
        return length;
    }

    private static double interiorMeanY(List<MetricPoint> points) {
        return points.subList(1, points.size() - 1).stream()
            .mapToDouble(MetricPoint::yMeters).average().orElse(Double.NaN);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonQuote(String value) {
        return "\"" + json(value) + "\"";
    }

    private static int firstOccurrence(byte[] value, byte[] token) {
        outer: for (int index = 0; index <= value.length - token.length; index++) {
            for (int offset = 0; offset < token.length; offset++) {
                if (value[index + offset] != token[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append(' ');
        }
        return messages.toString();
    }

    private record CliRun(int exit, String output) { }

    private enum Scene { RIDGE, PARALLEL, NO_SIGNAL }

    private enum RasterFixture { FINE, FINE_DENSE, COARSE }
}
