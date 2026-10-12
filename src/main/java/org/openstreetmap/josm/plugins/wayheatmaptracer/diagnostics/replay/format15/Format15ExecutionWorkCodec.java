package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExecutionWorkReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceWorkAccounting;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticExecutionPolicy;

/** Bounded coordinate-free work receipts; absent historical accounting remains absent. */
final class Format15ExecutionWorkCodec {
    static final String ARTIFACT = "execution-work.json";
    static final int MAX_BYTES = 128 * 1024;
    private static final Set<String> RUN_KEYS = Set.of("bStages", "otherEngineTransitions");
    private static final Set<String> STAGE_KEYS = Set.of("executionPolicy", "forwardBackwardScores",
            "kBestScores", "logicalKBestExtensions", "refusedScoreAttempts");

    private Format15ExecutionWorkCodec() { }

    static Format15Artifact artifact(List<Optional<ExecutionWorkReceipt>> receipts) {
        String json = "{\"schema\":1,\"runs\":" + json(receipts) + "}\n";
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("execution-work-byte-budget");
        }
        return Format15Artifact.text(ARTIFACT, json);
    }

    static List<Optional<ExecutionWorkReceipt>> read(Format15Artifact artifact) {
        if (artifact.sizeBytes() > MAX_BYTES) {
            throw new IllegalArgumentException("execution-work-byte-budget");
        }
        try {
            var document = Format15ArchiveReader.parseObject(artifact.bytes(), ARTIFACT);
            if (!document.keySet().equals(Set.of("schema", "runs"))
                    || !Long.valueOf(1L).equals(document.get("schema"))) {
                throw new IllegalArgumentException("execution-work-schema-invalid");
            }
            return read(document.get("runs"));
        } catch (Format15ArchiveException malformed) {
            throw new IllegalArgumentException("execution-work-schema-invalid", malformed);
        }
    }

    static String json(List<Optional<ExecutionWorkReceipt>> runs) {
        if (runs == null || runs.isEmpty() || runs.size() > 128) {
            throw new IllegalArgumentException("execution-work-run-budget");
        }
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < runs.size(); index++) {
            if (index != 0) json.append(',');
            json.append(json(runs.get(index)));
        }
        return json.append(']').toString();
    }

    static String json(Optional<ExecutionWorkReceipt> receipt) {
        if (receipt.isEmpty()) return "null";
        var work = receipt.orElseThrow();
        StringBuilder json = new StringBuilder("{\"otherEngineTransitions\":")
                .append(work.otherEngineTransitions()).append(",\"bStages\":[");
        for (int index = 0; index < work.bStages().size(); index++) {
            if (index != 0) json.append(',');
            var stage = work.bStages().get(index);
            requirePolicy(stage.executionPolicy());
            json.append("{\"executionPolicy\":\"").append(stage.executionPolicy())
                    .append("\",\"forwardBackwardScores\":").append(stage.forwardBackwardScores())
                    .append(",\"kBestScores\":").append(stage.kBestScores())
                    .append(",\"logicalKBestExtensions\":").append(stage.logicalKBestExtensions())
                    .append(",\"refusedScoreAttempts\":").append(stage.refusedScoreAttempts()).append('}');
        }
        return json.append("]}").toString();
    }

    static List<Optional<ExecutionWorkReceipt>> read(Object value) {
        if (!(value instanceof List<?> runs) || runs.isEmpty() || runs.size() > 128) {
            throw new IllegalArgumentException("execution-work-run-budget");
        }
        List<Optional<ExecutionWorkReceipt>> receipts = new ArrayList<>(runs.size());
        for (Object run : runs) {
            if (run == null) {
                receipts.add(Optional.empty());
                continue;
            }
            if (!(run instanceof Map<?, ?> map) || !map.keySet().equals(RUN_KEYS)
                    || !(map.get("bStages") instanceof List<?> stages)
                    || stages.isEmpty() || stages.size() > 2) {
                throw new IllegalArgumentException("execution-work-run-invalid");
            }
            List<InferenceWorkAccounting> parsed = new ArrayList<>(stages.size());
            for (Object stage : stages) {
                if (!(stage instanceof Map<?, ?> row) || !row.keySet().equals(STAGE_KEYS)
                        || !(row.get("executionPolicy") instanceof String policy)) {
                    throw new IllegalArgumentException("execution-work-stage-invalid");
                }
                requirePolicy(policy);
                parsed.add(new InferenceWorkAccounting(policy, number(row, "forwardBackwardScores"),
                        number(row, "kBestScores"), number(row, "logicalKBestExtensions"),
                        number(row, "refusedScoreAttempts")));
            }
            receipts.add(Optional.of(new ExecutionWorkReceipt(parsed, number(map, "otherEngineTransitions"))));
        }
        return List.copyOf(receipts);
    }

    private static long number(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof Long value) || value < 0L) {
            throw new IllegalArgumentException("execution-work-number-invalid");
        }
        return value;
    }

    private static void requirePolicy(String policy) {
        if (!ProbabilisticExecutionPolicy.VERSION.equals(policy)
                && !ProbabilisticExecutionPolicy.CURRENT_VERSION.equals(policy)) {
            throw new IllegalArgumentException("execution-work-policy-invalid");
        }
    }
}
