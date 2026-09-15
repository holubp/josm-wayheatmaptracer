package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;

/** Bounded reader for the manifest emitted by {@code v022_corpus.py}. */
final class Format15CorpusManifest {
    static final String SCHEMA = "wayheatmaptracer-v022-corpus-1";
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CASES = 10_000;
    private static final int MAX_ERRORS = 10_000;

    record ExpectedRoute(double minimumLengthMeters, double minimumDirectSupportMeters,
            OptionalDouble interiorMeanYMinimum, OptionalDouble interiorMeanYMaximum) {
        ExpectedRoute {
            if (!finiteNonnegative(minimumLengthMeters)
                    || !finiteNonnegative(minimumDirectSupportMeters)
                    || !finite(interiorMeanYMinimum) || !finite(interiorMeanYMaximum)
                    || interiorMeanYMinimum.isPresent() && interiorMeanYMaximum.isPresent()
                            && interiorMeanYMinimum.getAsDouble()
                                    > interiorMeanYMaximum.getAsDouble()) {
                throw new IllegalArgumentException("Invalid expected route invariants");
            }
        }

        static ExpectedRoute defaults() {
            return new ExpectedRoute(0.0, 0.0, OptionalDouble.empty(),
                    OptionalDouble.empty());
        }

        private static boolean finite(OptionalDouble value) {
            return value.isEmpty() || Double.isFinite(value.getAsDouble());
        }

        private static boolean finiteNonnegative(double value) {
            return Double.isFinite(value) && value >= 0.0;
        }
    }

    record InventoryError(String source, String code) {
        InventoryError {
            if (source == null || source.isBlank() || source.length() > 240
                    || code == null || !code.matches("[A-Z0-9_]{1,80}")) {
                throw new IllegalArgumentException("Malformed corpus inventory error");
            }
            Format15Safety.requireSafeExportedMetadata(source);
        }
    }

    record Case(String caseId, Path sourcePath, String outerSha256, String bundleName,
            String bundleSha256, long byteSize, ReplayLevel replayCapability,
            List<String> missingInputs, ExpectedRoute expectedRoute) {
        Case {
            if (caseId == null || !caseId.matches("[A-Za-z0-9._-]{1,80}")
                    || sourcePath == null || outerSha256 == null
                    || bundleName == null || bundleName.isBlank()
                    || bundleSha256 == null || replayCapability == null
                    || missingInputs == null || expectedRoute == null
                    || byteSize < 0 || byteSize > Format15Safety.MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Malformed corpus case");
            }
            Format15Safety.requiredHash(outerSha256, "outerSha256");
            Format15Safety.requiredHash(bundleSha256, "bundleSha256");
            missingInputs = List.copyOf(missingInputs);
            for (String missing : missingInputs) {
                if (missing == null || !missing.matches("[A-Za-z0-9._-]{1,120}")) {
                    throw new IllegalArgumentException("Unsafe missing-input code");
                }
            }
        }
    }

    private final List<Case> cases;
    private final List<InventoryError> errors;

    private Format15CorpusManifest(List<Case> cases, List<InventoryError> errors) {
        this.cases = List.copyOf(cases);
        this.errors = List.copyOf(errors);
    }

    List<Case> cases() {
        return cases;
    }

    List<InventoryError> errors() {
        return errors;
    }

    static Format15CorpusManifest read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Corpus manifest is not a regular file");
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Corpus manifest exceeds byte budget");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != size) {
            throw new IllegalArgumentException("Corpus manifest changed while reading");
        }
        Map<String, Object> root = Format15ArchiveReader.parseObject(bytes,
                "corpus manifest");
        if (!SCHEMA.equals(string(root, "schema"))) {
            throw new IllegalArgumentException("Unsupported corpus manifest schema");
        }
        List<InventoryError> errors = parseErrors(root.get("errors"));
        Object rawCases = root.get("cases");
        if (!(rawCases instanceof List<?> values) || values.size() > MAX_CASES) {
            throw new IllegalArgumentException("Corpus manifest case inventory is invalid");
        }
        List<Case> result = new ArrayList<>(values.size());
        Set<String> ids = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Corpus manifest case is not an object");
            }
            Case parsed = parseCase(map);
            if (!ids.add(parsed.caseId())) {
                throw new IllegalArgumentException("Corpus manifest has duplicate case IDs");
            }
            result.add(parsed);
        }
        if (result.isEmpty() && errors.isEmpty()) {
            throw new IllegalArgumentException("Corpus manifest has no cases or inventory errors");
        }
        return new Format15CorpusManifest(result, errors);
    }

    private static List<InventoryError> parseErrors(Object value) {
        if (!(value instanceof List<?> list) || list.size() > MAX_ERRORS) {
            throw new IllegalArgumentException("Corpus inventory-error list is invalid");
        }
        List<InventoryError> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Corpus inventory error is not an object");
            }
            result.add(new InventoryError(string(map, "source"), string(map, "code")));
        }
        return List.copyOf(result);
    }

    private static Case parseCase(Map<?, ?> map) {
        String caseId = string(map, "caseId");
        Path sourcePath = Path.of(string(map, "sourcePath"));
        String outerHash = string(map, "outerSha256");
        String bundleName = string(map, "bundleName");
        String bundleHash = string(map, "bundleSha256");
        long byteSize = integer(map, "byteSize");
        ReplayLevel capability;
        try {
            capability = ReplayLevel.valueOf(string(map, "replayCapability"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown corpus replay capability", exception);
        }
        List<String> missing = strings(map.get("missingInputs"));
        ExpectedRoute expected = expectedRoute(map.get("expectedRoute"));
        return new Case(caseId, sourcePath, outerHash, bundleName, bundleHash,
                byteSize, capability, missing, expected);
    }

    private static ExpectedRoute expectedRoute(Object value) {
        if (value == null) {
            return ExpectedRoute.defaults();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected route invariants must be an object");
        }
        Set<String> supported = Set.of("minimumLengthMeters",
                "minimumDirectSupportMeters", "interiorMeanYMin", "interiorMeanYMax");
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !supported.contains(name)) {
                throw new IllegalArgumentException("Unsupported expected route invariant");
            }
        }
        return new ExpectedRoute(numberOr(map, "minimumLengthMeters", 0.0),
                numberOr(map, "minimumDirectSupportMeters", 0.0),
                optionalNumber(map, "interiorMeanYMin"),
                optionalNumber(map, "interiorMeanYMax"));
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Corpus manifest string is missing: " + key);
        }
        return text;
    }

    private static long integer(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.longValue() != number.doubleValue()) {
            throw new IllegalArgumentException("Corpus manifest integer is invalid: " + key);
        }
        return number.longValue();
    }

    private static double numberOr(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Corpus manifest number is invalid: " + key);
        }
        return number.doubleValue();
    }

    private static OptionalDouble optionalNumber(Map<?, ?> map, String key) {
        return map.containsKey(key) ? OptionalDouble.of(numberOr(map, key, 0.0))
                : OptionalDouble.empty();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 1_000) {
            throw new IllegalArgumentException("Corpus missing-input inventory is invalid");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("Corpus missing-input code is invalid");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }
}
