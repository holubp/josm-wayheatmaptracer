package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** Offline corpus replay command used by the CLI and integration tests. */
public final class ProductionReplayCommand {
    private static final int MAX_ABLATION_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;

    private ProductionReplayCommand() {
    }

    /** Executes one bounded offline replay invocation and returns its process exit code. */
    public static int run(String[] arguments) throws IOException {
        Arguments args = Arguments.parse(arguments);
        requireEmptyAblation(args.ablationConfig());
        Format15CorpusManifest manifest = Format15CorpusManifest.read(args.manifest());
        List<CaseResult> results = new ArrayList<>();
        int succeeded = 0;
        for (Format15CorpusManifest.Case item : manifest.cases()) {
            for (TrackerMode engine : args.engines()) {
                CaseResult result = replay(item, engine);
                results.add(result);
                if (result.status.equals("ok")) {
                    succeeded++;
                }
            }
        }
        String output = output(results, succeeded, manifest.errors());
        if (output.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("replay-output-budget");
        }
        Path parent = args.output().toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("replay-output-parent-missing");
        }
        Files.createDirectories(parent);
        Files.writeString(args.output(), output, StandardCharsets.UTF_8);
        return args.strict() && (succeeded != results.size() || !manifest.errors().isEmpty())
                ? 2 : 0;
    }

    private static CaseResult replay(Format15CorpusManifest.Case item,
            TrackerMode engine) {
        TrackerMode capturedEngine = null;
        TraceHypothesisSet inference = null;
        String fidelity = "UNAVAILABLE";
        String quality = "NOT_EVALUATED";
        String reason = "";
        try {
            if (missingFor(item.replayCapability(), item.missingInputs())) {
                throw new ReplayMismatchException("manifest-inputs-missing");
            }
            if (item.replayCapability() == ReplayLevel.RASTER_INFERENCE
                    || item.replayCapability() == ReplayLevel.FULL_EDIT_PLAN) {
                fidelity = "UNSUPPORTED_LEVEL";
                throw new ReplayMismatchException("unsupported-replay-level");
            }
            Format15Archive archive = Format15NestedArchiveReader.read(item);
            Optional<ScalarReplayExpectation> expected;
            try {
                expected = ScalarReplayExpectation.read(archive);
            } catch (ReplayMismatchException invalid) {
                fidelity = "MISMATCH";
                throw invalid;
            }
            FrozenReplayInput input = archive.artifact("frozen-input.bin")
                    .map(Format15Artifact::bytes)
                    .map(FrozenReplayCodec::decode)
                    .orElseThrow(() -> new ReplayMismatchException(
                            "frozen-production-input-missing"));
            capturedEngine = input.request().engine();
            try {
                expected.ifPresent(value -> value.validateBinding(archive, input));
            } catch (ReplayMismatchException stale) {
                fidelity = "MISMATCH";
                throw stale;
            }
            Format15ReplayRunner.Result scalar = Format15ReplayRunner.replay(archive,
                    ReplayLevel.SCALAR_INFERENCE, archive.sourceIdentityHash(),
                    archive.parameterHash(), engine);
            inference = scalar.inference();
            if (item.replayCapability() == ReplayLevel.SCALAR_INFERENCE) {
                if (expected.isPresent() && expected.orElseThrow().requestedEngine() == engine) {
                    fidelity = expected.orElseThrow().matches(scalar) ? "MATCH" : "MISMATCH";
                }
            } else {
                fidelity = "UNSUPPORTED_LEVEL";
            }
            try {
                ProductionReplayValidator.validateScalar(scalar);
                if (item.replayCapability() == ReplayLevel.FINAL_GEOMETRY) {
                    Format15ReplayRunner.Result finalResult = Format15ReplayRunner.replay(archive,
                            ReplayLevel.FINAL_GEOMETRY, archive.sourceIdentityHash(),
                            archive.parameterHash(), engine);
                    ProductionReplayValidator.validateFinal(finalResult, input,
                        item.expectedRoute());
                }
                quality = "PASS";
            } catch (ReplayMismatchException mismatch) {
                quality = "FAIL";
                reason = safeReason(mismatch);
            }
            if (fidelity.equals("MISMATCH")) {
                reason = "scalar-output-mismatch";
            }
            String status = quality.equals("PASS") && !fidelity.equals("MISMATCH")
                    ? "ok" : "failed";
            return new CaseResult(item.caseId(), item.replayCapability(), engine,
                    capturedEngine, inference, fidelity, quality, status, reason);
        } catch (IOException | RuntimeException exception) {
            return new CaseResult(item.caseId(), item.replayCapability(), engine,
                    capturedEngine, inference, fidelity, quality, "failed",
                    safeReason(exception));
        }
    }

    private static boolean missingFor(ReplayLevel level, List<String> missingInputs) {
        for (String missing : missingInputs) {
            if (!missing.equals("complete-edit-plan")
                    && !missing.equals("incident-relations")) {
                return true;
            }
            if (level == ReplayLevel.FULL_EDIT_PLAN) {
                return true;
            }
        }
        return false;
    }

    private static void requireEmptyAblation(String value) throws IOException {
        if (value == null) {
            return;
        }
        byte[] bytes;
        if ("{}".equals(value.trim())) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
        } else {
            Path path = Path.of(value);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > MAX_ABLATION_BYTES) {
                throw new IllegalArgumentException("ablation-config-invalid");
            }
            bytes = Files.readAllBytes(path);
        }
        Map<String, Object> config = Format15ArchiveReader.parseObject(bytes,
                "ablation config");
        if (!config.isEmpty()) {
            throw new IllegalArgumentException("unsupported-ablation-option");
        }
    }

    private static String output(List<CaseResult> results, int succeeded,
            List<Format15CorpusManifest.InventoryError> inventoryErrors) {
        int requested = Math.addExact(results.size(), inventoryErrors.size());
        int failed = Math.addExact(results.size() - succeeded, inventoryErrors.size());
        StringBuilder json = new StringBuilder("{\"schema\":\"wayheatmaptracer-v022-replay-1\",")
                .append("\"summary\":{\"requested\":").append(requested)
                .append(",\"succeeded\":").append(succeeded)
                .append(",\"failed\":").append(failed)
                .append(",\"inventoryErrors\":").append(inventoryErrors.size())
                .append("},\"results\":[");
        for (int index = 0; index < results.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            CaseResult result = results.get(index);
            json.append("{\"caseId\":").append(quote(result.caseId))
                    .append(",\"replayLevel\":").append(quote(result.level.name()))
                    .append(",\"engine\":").append(quote(engineName(result.engine)))
                    .append(",\"capturedEngine\":")
                    .append(result.capturedEngine == null ? "null"
                            : quote(engineName(result.capturedEngine)))
                    .append(",\"executedEngine\":").append(quote(engineName(result.engine)))
                    .append(",\"actualStatus\":")
                    .append(result.inference == null ? "null"
                            : quote(result.inference.status().name()))
                    .append(",\"alternativesTruncated\":")
                    .append(result.inference == null ? "null"
                            : result.inference.alternativesTruncated())
                    .append(",\"evaluatedStates\":")
                    .append(result.inference == null ? "null"
                            : result.inference.evaluatedStates())
                    .append(",\"evaluatedTransitions\":")
                    .append(result.inference == null ? "null"
                            : result.inference.evaluatedTransitions())
                    .append(",\"fidelityStatus\":").append(quote(result.fidelityStatus))
                    .append(",\"qualityStatus\":").append(quote(result.qualityStatus))
                    .append(",\"status\":").append(quote(result.status))
                    .append(",\"reason\":").append(quote(result.reason)).append('}');
        }
        json.append("],\"inventoryFailures\":[");
        for (int index = 0; index < inventoryErrors.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Format15CorpusManifest.InventoryError error = inventoryErrors.get(index);
            json.append("{\"source\":").append(quote(error.source()))
                    .append(",\"code\":").append(quote(error.code())).append('}');
        }
        return json.append("]}\n").toString();
    }

    private static String engineName(TrackerMode engine) {
        return switch (engine) {
            case CORRIDOR_AWARE -> "A";
            case PROBABILISTIC -> "B";
            case HYBRID -> "HYBRID";
            case DIRECTIONAL_IMAGE -> "IMAGE";
            case LEGACY_V02 -> "LEGACY";
        };
    }

    private static String safeReason(Throwable failure) {
        if (failure instanceof IOException) {
            return "archive-io-failure";
        }
        String message = failure.getMessage();
        if (message != null && message.matches("[a-z0-9-]{1,120}")) {
            return message;
        }
        return "replay-validation-failure";
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\');
            }
            if (character < 0x20) {
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }

    private record CaseResult(String caseId, ReplayLevel level, TrackerMode engine,
            TrackerMode capturedEngine, TraceHypothesisSet inference,
            String fidelityStatus, String qualityStatus, String status, String reason) {
    }

    private record Arguments(Path manifest, List<TrackerMode> engines,
            Path output, boolean strict, String ablationConfig) {
        static Arguments parse(String[] arguments) {
            if (arguments == null) {
                throw new IllegalArgumentException("replay-arguments-missing");
            }
            Path manifest = null;
            Path output = null;
            List<TrackerMode> engines = List.of();
            boolean strict = false;
            boolean offline = false;
            String ablation = null;
            for (int index = 0; index < arguments.length; index++) {
                String argument = arguments[index];
                switch (argument) {
                    case "--strict" -> strict = true;
                    case "--offline" -> offline = true;
                    case "--manifest" -> {
                        manifest = Path.of(next(arguments, ++index));
                    }
                    case "--output" -> {
                        output = Path.of(next(arguments, ++index));
                    }
                    case "--engines" -> {
                        engines = engines(next(arguments, ++index));
                    }
                    case "--ablation-config" -> {
                        ablation = next(arguments, ++index);
                    }
                    default -> throw new IllegalArgumentException(
                            "unknown-replay-option");
                }
            }
            if (manifest == null || output == null || engines.isEmpty() || !offline) {
                throw new IllegalArgumentException("incomplete-replay-arguments");
            }
            return new Arguments(manifest, engines, output, strict, ablation);
        }

        private static String next(String[] arguments, int index) {
            if (index >= arguments.length || arguments[index].isBlank()) {
                throw new IllegalArgumentException("replay-option-value-missing");
            }
            return arguments[index];
        }

        private static List<TrackerMode> engines(String value) {
            List<TrackerMode> result = new ArrayList<>();
            for (String name : value.split(",", -1)) {
                TrackerMode engine = switch (name) {
                    case "A" -> TrackerMode.CORRIDOR_AWARE;
                    case "B" -> TrackerMode.PROBABILISTIC;
                    case "HYBRID" -> TrackerMode.HYBRID;
                    case "IMAGE" -> TrackerMode.DIRECTIONAL_IMAGE;
                    default -> throw new IllegalArgumentException(
                            "unknown-replay-engine");
                };
                if (result.contains(engine)) {
                    throw new IllegalArgumentException("duplicate-replay-engine");
                }
                result.add(engine);
            }
            return List.copyOf(result);
        }
    }
}
