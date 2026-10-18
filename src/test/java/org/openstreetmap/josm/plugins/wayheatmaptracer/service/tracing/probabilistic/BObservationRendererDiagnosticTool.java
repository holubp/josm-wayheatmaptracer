package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;

/** One-shot public SELECT raster comparison for Math.exp versus StrictMath.exp. */
public final class BObservationRendererDiagnosticTool {
    private static final String ID = "sparse-crossing-noisy-strands-vs-persistent-parallel/select/v4/right";

    private BObservationRendererDiagnosticTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This diagnostic has no configurable inputs");
        var directory = prepareOutputDirectory(java.nio.file.Path.of("build/reports/public-observation-renderer"));
        var catalog = BIdentificationCatalog.publicCatalog();
        var access = BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);
        var key = catalog.keyForMaterialization(ID, access);
        var definition = BIdentificationCatalog.definition(key, access);

        BIdentificationCatalog.Scene warmScene = null;
        for (int i = 0; i < 8; i++) {
            warmScene = BIdentificationScenes.render(key, access);
            var sourceKey = BObservationIdentificationTool.sourceKey(definition, warmScene);
            System.out.println("warm=" + i + " nativeIdentity=" + warmScene.evidence().sourceIdentity()
                    + " nativeSourceKey=" + sourceKey.nativeHash() + " frame=" + sourceKey.nativeFrame());
        }

        Method intensity = BIdentificationScenes.class.getDeclaredMethod("intensity",
                BIdentificationCatalog.CaseDefinition.class, MetricPoint.class);
        intensity.setAccessible(true);
        Method nativeHash = BIdentificationScenes.class.getDeclaredMethod("nativeHash", double[].class, boolean[].class);
        nativeHash.setAccessible(true);

        double pitch = definition.sourcePitchMeters();
        double samplePhase = definition.phase() * pitch;
        int width = (int) Math.ceil(184.0 / pitch) + 2;
        int height = (int) Math.ceil(64.0 / pitch) + 2;
        var transform = RasterMetricTransform.metricGrid(
                new MetricPoint(-32.0 - samplePhase, 62.0 + samplePhase), pitch, 0.0, 0.0, -pitch);
        int pixels = Math.multiplyExact(width, height);
        double[] originalMath = new double[pixels];
        double[] formulaMath = new double[pixels];
        double[] formulaStrict = new double[pixels];
        boolean[] valid = new boolean[pixels];
        Arrays.fill(valid, true);
        MessageDigest argumentDigest = sha256();
        MessageDigest mathOutputDigest = sha256();
        MessageDigest strictOutputDigest = sha256();
        ByteBuffer bytes = ByteBuffer.allocate(Double.BYTES).order(ByteOrder.BIG_ENDIAN);
        long mismatchOriginalMath = 0;
        long mismatchMathStrict = 0;
        long maximumMathStrictUlps = 0;
        long maximumOriginalMathUlps = 0;

        for (int yIndex = 0; yIndex < height; yIndex++) {
            for (int xIndex = 0; xIndex < width; xIndex++) {
                int index = yIndex * width + xIndex;
                MetricPoint point = transform.pixelCenterToMetric(xIndex, yIndex);
                originalMath[index] = (double) intensity.invoke(null, definition, point);
                formulaMath[index] = scalar(point.xMeters(), point.yMeters(), false, argumentDigest, bytes);
                formulaStrict[index] = scalar(point.xMeters(), point.yMeters(), true, null, null);
                if (Double.doubleToLongBits(originalMath[index]) != Double.doubleToLongBits(formulaMath[index])) {
                    mismatchOriginalMath++;
                    maximumOriginalMathUlps = Math.max(maximumOriginalMathUlps,
                            ulpDistance(originalMath[index], formulaMath[index]));
                }
                if (Double.doubleToLongBits(formulaMath[index]) != Double.doubleToLongBits(formulaStrict[index])) {
                    mismatchMathStrict++;
                    maximumMathStrictUlps = Math.max(maximumMathStrictUlps,
                            ulpDistance(formulaMath[index], formulaStrict[index]));
                }
                update(mathOutputDigest, bytes, formulaMath[index]);
                update(strictOutputDigest, bytes, formulaStrict[index]);
            }
        }

        String actualNative = (String) nativeHash.invoke(null, originalMath, valid);
        String mathNative = (String) nativeHash.invoke(null, formulaMath, valid);
        String strictNative = (String) nativeHash.invoke(null, formulaStrict, valid);
        System.out.println("java.version=" + System.getProperty("java.version")
                + " os.arch=" + System.getProperty("os.arch"));
        System.out.println("case=" + ID + " pitch=" + pitch + " phase=" + definition.phase()
                + " dimensions=" + width + "x" + height + " pixels=" + pixels);
        System.out.println("expArgumentDigest=" + hex(argumentDigest.digest()));
        System.out.println("mathOutputDigest=" + hex(mathOutputDigest.digest()));
        System.out.println("strictOutputDigest=" + hex(strictOutputDigest.digest()));
        System.out.println("actualRendererNativeHash=" + actualNative);
        System.out.println("mathFormulaNativeHash=" + mathNative);
        System.out.println("strictFormulaNativeHash=" + strictNative);
        System.out.println("actualVsMathDifferingPixels=" + mismatchOriginalMath
                + " maxUlps=" + maximumOriginalMathUlps);
        System.out.println("mathVsStrictDifferingPixels=" + mismatchMathStrict
                + " maxUlps=" + maximumMathStrictUlps);
        decodeBoundReceipt(warmScene, definition, access, directory);
        if (mismatchOriginalMath != 0) {
            throw new AssertionError("The cloned Math formula does not reproduce the original renderer");
        }
    }

    private static double scalar(double x, double y, boolean strict, MessageDigest argumentDigest,
            ByteBuffer argumentBytes) {
        double phase = x / 80.0 - Math.floor(x / 80.0);
        double splitShape = 0.8 * (4.0 * Math.abs(phase - 0.5) - 1.0);
        double splitShift = 2.5 + splitShape;
        double firstZ = (y - (26.0 + splitShift)) / 0.8;
        double secondZ = (y - (34.0 + splitShift)) / 0.8;
        if (argumentDigest != null) {
            update(argumentDigest, argumentBytes, -0.5 * firstZ * firstZ);
            update(argumentDigest, argumentBytes, -0.5 * secondZ * secondZ);
        }
        double first = strict ? StrictMath.exp(-0.5 * firstZ * firstZ) : Math.exp(-0.5 * firstZ * firstZ);
        double second = strict ? StrictMath.exp(-0.5 * secondZ * secondZ) : Math.exp(-0.5 * secondZ * secondZ);
        return Math.max(0.0, Math.min(1.0, 0.02 + 0.44 * first + 0.44 * second));
    }

    private static long ulpDistance(double first, double second) {
        return Math.abs(orderedBits(first) - orderedBits(second));
    }

    private static long orderedBits(double value) {
        long bits = Double.doubleToLongBits(value);
        return bits < 0 ? Long.MIN_VALUE - bits : bits;
    }

    private static void update(MessageDigest digest, ByteBuffer buffer, double value) {
        buffer.clear();
        buffer.putDouble(value);
        digest.update(buffer.array());
    }

    private static MessageDigest sha256() throws Exception {
        return MessageDigest.getInstance("SHA-256");
    }

    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }

    /** Only decode saved indices after BOTH original input and complete fact hashes match. */
    private static void decodeBoundReceipt(BIdentificationCatalog.Scene scene,
            BIdentificationCatalog.CaseDefinition definition,
            BIdentificationCatalog.EvaluationAccess access, java.nio.file.Path directory) throws Exception {
        var cancellation = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE;
        java.nio.file.Path fixture = java.nio.file.Path.of(
                "src/test/resources/v022/observation-identification/rejected-parallel-select-v4.json");
        byte[] bytes = BIdentificationReceipt.readVerifiedBytes(fixture,
                "0b26bf8711dad9187226af78d70a354cc06c8e9030c6c410ed97ef0086e9b37d", cancellation, () -> {});
        var receipt = BIdentificationReceipt.parse(bytes);
        if (!ID.equals(receipt.get("case")) || definition.key().split() != BIdentificationCatalog.Split.SELECT)
            throw new IllegalArgumentException("Only the fixed public SELECT receipt is authorized");
        var expectedSource = (java.util.Map<?, ?>) receipt.get("source");
        var actualSource = BObservationIdentificationTool.sourceKey(definition, scene);
        var report = new java.util.LinkedHashMap<String, Object>();
        report.put("schema", 1);
        report.put("publicDiagnosticOnly", true);
        report.put("modelAcceptance", false);
        report.put("rasterReads", access.rasterReads());
        report.put("labelReads", access.labelReads());
        report.put("holdoutReads", 0);
        report.put("case", ID);
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("architecture", System.getProperty("os.arch"));
        report.put("originalGeneratingSourceHash", "ccf18c7650068b444824c444d92ba13404fd7079d8ab06653cb82d7cdc8ff958");
        report.put("currentDiagnosticSourceHash", BIdentificationReceipt.sourceHash());
        report.put("fixtureSHA256", "0b26bf8711dad9187226af78d70a354cc06c8e9030c6c410ed97ef0086e9b37d");
        report.put("expectedNativeHash", expectedSource.get("nativeHash"));
        report.put("actualNativeHash", actualSource.nativeHash());
        report.put("expectedFactsHash", receipt.get("factsHash"));
        report.put("status", "NATIVE_INPUT_MISMATCH");
        try (var owner = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(
                64L * 1024 * 1024).rootOwner()) {
            var factory = new ProbabilisticProfileFactory();
            var profiles = factory.create(scene.source(), factory.profileChainage(scene.source(), 1.5),
                    25, true, scene.evidence(), scene.evidence().fields().get("scalar"),
                    EvidenceModelParameters.defaults(), cancellation, true, owner);
            var facts = new BIdentificationFactsExtractor().extract(scene.evidence(), profiles, cancellation, owner);
            var lattices = BObservationIdentificationTool.admitLattices(
                    profiles, EvidenceModelParameters.defaults(), cancellation, owner).lattices();
            String factsHash = BIdentificationReceipt.factsHash(facts, lattices, cancellation);
            report.put("actualFactsHash", factsHash);
            boolean nativeMatch = actualSource.nativeHash().equals(expectedSource.get("nativeHash"))
                    && actualSource.nativeFrame().equals(expectedSource.get("nativeInputFrame"))
                    && actualSource.sourceGroup().equals(expectedSource.get("sourceGroup"))
                    && actualSource.capability().name().equals(expectedSource.get("capability"));
            if (nativeMatch) report.put("status", "FACTS_MISMATCH");
            if (nativeMatch && factsHash.equals(receipt.get("factsHash"))) {
                var geometry = (java.util.Map<?, ?>) receipt.get("rawGeometry");
                var indices = (java.util.List<?>) geometry.get("mapStateIndices");
                if (indices.size() != profiles.size()) throw new IllegalArgumentException("Invalid receipt state count");
                StringBuilder csv = new StringBuilder("index,state,cell_count,x_m,y_m,x_hex,y_hex,lower_branch_residual_m\n");
                for (int i = 0; i < indices.size(); i++) {
                    int state = ((Number) indices.get(i)).intValue();
                    var profile = profiles.get(i);
                    var cell = lattices.get(i).cells().get(state);
                    double x = profile.anchor().xMeters() + profile.normalUnit().xMeters() * cell.offsetMeters();
                    double y = profile.anchor().yMeters() + profile.normalUnit().yMeters() * cell.offsetMeters();
                    csv.append(i).append(',').append(state).append(',').append(lattices.get(i).cells().size())
                            .append(',').append(x).append(',').append(y).append(',').append(Double.toHexString(x))
                            .append(',').append(Double.toHexString(y)).append(',')
                            .append(x >= 40 && x <= 80 ? Double.toString(y - (27.7 + .04 * (x - 40))) : "UNAVAILABLE")
                            .append('\n');
                }
                java.nio.file.Files.writeString(directory.resolve("bound-geometry.csv"), csv);
                report.put("status", "BOUND_GEOMETRY_DECODED");
                report.put("pointCount", indices.size());
            }
        }
        java.nio.file.Files.writeString(directory.resolve("binding.json"),
                BIdentificationReceipt.canonical(report) + "\n");
        System.out.println("BOUND_RECEIPT " + BIdentificationReceipt.canonical(report));
    }

    static java.nio.file.Path prepareOutputDirectory(java.nio.file.Path parent) throws java.io.IOException {
        java.nio.file.Files.createDirectories(parent);
        return java.nio.file.Files.createDirectory(parent.resolve("receipt"));
    }
}
