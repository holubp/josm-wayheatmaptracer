package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** One-shot public SELECT raster comparison for Math.exp versus StrictMath.exp. */
public final class BObservationRendererDiagnosticTool {
    private static final String ID = "sparse-crossing-noisy-strands-vs-persistent-parallel/select/v4/right";
    private static final int MAX_OBJECTIVE_EVIDENCE_ROWS = 3 * 81;
    private static final int MAX_OBJECTIVE_EVIDENCE_LINE_BYTES = 64 * 1024;
    private static final long MAX_OBJECTIVE_EVIDENCE_TOTAL_BYTES = 2L * 1024 * 1024;
    private static final String OBJECTIVE_EVIDENCE_FILE = "objective-evidence.jsonl";
    private record MapCostRow(int index, int state, String branchLabel, boolean exactAnchor,
            boolean mandatory, double xMeters, double yMeters, double unaryEnergy, double guideEnergy,
            double pairOrientationEnergy, double tripleTurnEnergy, double orientationCertainty,
            double orientationReliability) { }
    private record RescoreResult(double energy, List<MapCostRow> rows) {
        RescoreResult { rows = List.copyOf(rows); }
    }
    private record BranchWitness(String branchLabel, List<Integer> stateIndices, List<MetricPoint> points,
            List<MetricPoint> sourceCurve) {
        BranchWitness {
            stateIndices = List.copyOf(stateIndices);
            points = List.copyOf(points);
            sourceCurve = List.copyOf(sourceCurve);
        }
    }
    private record ObjectiveProfile(InferenceProfile inference, BIdentificationFacts.ProfileFacts facts,
            BObservationMixture mixture, BObservationMixture.ReducedRows reduced) { }
    private record EvidenceFileReceipt(int rowCount, long byteCount, String sha256) { }

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
                report.put("status", "ORIGINAL_NATIVE_AND_FACTS_MATCH_OBJECTIVE_PENDING");
                report.put("pointCount", indices.size());
                writeBinding(directory, report);
                report.put("status", "BOUND_GEOMETRY_DECODED");
                writeObjective(directory, scene, profiles, facts, lattices, indices,
                        receipt, geometry, cancellation, owner);
                writeBinding(directory, report);
            }
        }
        writeBinding(directory, report);
        System.out.println("BOUND_RECEIPT " + BIdentificationReceipt.canonical(report));
    }

    private static void writeBinding(java.nio.file.Path directory, Map<String, Object> report) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("binding.json"),
                BIdentificationReceipt.canonical(report) + "\n");
    }

    private static void writeObjective(java.nio.file.Path directory, BIdentificationCatalog.Scene scene,
            List<ProbabilisticProfile> sourceProfiles,
            List<BIdentificationFacts.ProfileFacts> facts, List<ProbabilisticStateLattice> lattices,
            List<?> rawIndices, Map<?, ?> receipt, Map<?, ?> receiptGeometry, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) throws Exception {
        if (facts.size() != 81 || sourceProfiles.size() != 81 || lattices.size() != 81 || rawIndices.size() != 81)
            throw new IllegalArgumentException("The fixed public receipt requires exactly 81 original admitted profiles");
        var parameterFields = (Map<?, ?>) receipt.get("parameters");
        var kernelParameters = new BIdentifiedObservationKernel.Parameters(
                ((Number) parameterFields.get("G")).doubleValue(),
                ((Number) parameterFields.get("lambda")).doubleValue(),
                ((Number) parameterFields.get("B")).doubleValue());
        EvidenceModelParameters modelParameters = EvidenceModelParameters.defaults();
        var kernel = new BIdentifiedObservationKernel(kernelParameters);
        if (!modelIdentityMatches(kernel, receipt))
            throw new IllegalArgumentException("Original receipt and raw graph model identities must both match");
        var objectiveProfiles = new ArrayList<ObjectiveProfile>(facts.size());
        for (int index = 0; index < facts.size(); index++) {
            cancellation.checkpoint();
            objectiveProfiles.add(evaluateObjectiveProfile(facts.get(index), lattices.get(index),
                    modelParameters, kernel, cancellation, owner));
        }
        List<InferenceProfile> profiles = objectiveProfiles.stream().map(ObjectiveProfile::inference).toList();
        var mapStates = parseStates(rawIndices, lattices);
        double registeredEnergy = ((Number) receiptGeometry.get("mapEnergy")).doubleValue();
        var map = rescoreSavedMap(profiles, mapStates, modelParameters);
        if (Double.doubleToRawLongBits(map.energy()) != Double.doubleToRawLongBits(registeredEnergy))
            throw new IllegalStateException("Original saved MAP energy does not match exact original recurrence");

        List<List<MetricPoint>> branches = scene.truth().regions().stream()
                .flatMap(region -> region.acceptableCurves().stream()).toList();
        var witnesses = buildBranchWitnesses(profiles, lattices, mapStates, branches);
        var pathRows = new ArrayList<Map<String, Object>>();
        var evidenceRows = new ArrayList<Map<String, Object>>(MAX_OBJECTIVE_EVIDENCE_ROWS);
        var allRows = new StringBuilder("path,index,state,branch,exact_anchor,mandatory,x_m,y_m,unary_energy,guide_energy,pair_orientation_energy,triple_turn_energy,orientation_certainty,orientation_reliability\n");
        appendCostRows(allRows, "map", map);
        pathRows.add(pathSummary("map", map, objectiveProfiles, sourceProfiles, null, evidenceRows,
                scene, cancellation, owner));
        for (var witness : witnesses) {
            var rescored = rescoreSavedMap(profiles, witness.stateIndices(), modelParameters);
            String pathId = witness.branchLabel();
            appendCostRows(allRows, pathId, rescored);
            pathRows.add(pathSummary(pathId, rescored, objectiveProfiles, sourceProfiles,
                    witness, evidenceRows, scene, cancellation, owner));
        }
        if (witnesses.size() != 2) {
            pathRows.add(Map.of("status", "UNAVAILABLE", "reason", "TWO_SOURCE_BRANCHES_NOT_AVAILABLE"));
        }
        if (pathRows.stream().mapToInt(path -> {
                Object count = path.get("pointCount"); return count instanceof Number number ? number.intValue() : 0;
            }).sum() > 3 * sourceProfiles.size())
            throw new IllegalStateException("Objective diagnostic exceeded its fixed row bound");

        var objective = new LinkedHashMap<String, Object>();
        objective.put("schema", 1);
        objective.put("publicDiagnosticOnly", true);
        objective.put("modelAcceptance", false);
        objective.put("case", ID);
        objective.put("modelHash", kernel.identityHash());
        objective.put("parameters", Map.of("G", kernelParameters.gain(), "lambda", kernelParameters.nuisanceMultiplier(),
                "B", kernelParameters.directSpanSlope()));
        objective.put("rawMapEnergy", registeredEnergy);
        objective.put("rescoredMapEnergy", map.energy());
        objective.put("mapEnergyRawBitsMatch", true);
        objective.put("logMeasure", "UNAVAILABLE_NOT_SUBTRACTED_FROM_ENERGY");
        objective.put("rowCount", pathRows.stream().mapToInt(path -> {
                Object count = path.get("pointCount"); return count instanceof Number number ? number.intValue() : 0;
            }).sum());
        objective.put("paths", pathRows);
        EvidenceFileReceipt evidenceReceipt = writeObjectiveEvidence(directory, evidenceRows);
        objective.put("stateEvidenceFile", OBJECTIVE_EVIDENCE_FILE);
        objective.put("stateEvidenceRecords", evidenceReceipt.rowCount());
        objective.put("stateEvidenceBytes", evidenceReceipt.byteCount());
        objective.put("stateEvidenceSHA256", evidenceReceipt.sha256());
        java.nio.file.Files.writeString(directory.resolve("objective.json"),
                BIdentificationReceipt.canonical(objective) + "\n");
        java.nio.file.Files.writeString(directory.resolve("objective-costs.csv"), allRows.toString());
    }

    private static boolean modelIdentityMatches(BIdentifiedObservationKernel kernel, Map<?, ?> receipt) {
        if (kernel == null || receipt == null) return false;
        Object expected = receipt.get("modelHash");
        Object rawGeometry = receipt.get("rawGeometry");
        if (!(expected instanceof String hash) || !(rawGeometry instanceof Map<?, ?> graph)) return false;
        return hash.equals(kernel.identityHash()) && hash.equals(graph.get("modelHash"));
    }

    private static ObjectiveProfile evaluateObjectiveProfile(BIdentificationFacts.ProfileFacts facts,
            ProbabilisticStateLattice lattice, EvidenceModelParameters parameters,
            BIdentifiedObservationKernel kernel, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        cancellation.checkpoint();
        var transaction = owner.child("public-objective-rescore");
        try {
            var inference = BObservationAdapter.evaluate(facts, lattice, parameters, true, kernel,
                    cancellation, transaction);
            // Re-evaluate only the bounded component rows for the diagnostic receipt. The inference
            // profile above always comes from the shared adapter used by the original score path.
            var mixture = kernel.evaluate(facts, lattice, cancellation, transaction);
            var reduced = mixture.reduce(lattice, cancellation, transaction);
            var result = ProbabilisticInference.allocated(transaction, AttemptMemoryLedger.objectBytes(4L * Long.BYTES),
                    "objective-profile-receipt", () -> new ObjectiveProfile(inference, facts, mixture, reduced));
            cancellation.checkpoint();
            transaction.transferTo(owner);
            return result;
        } finally {
            transaction.close();
        }
    }

    private static Map<String, Object> pathSummary(String pathId, RescoreResult score,
            List<ObjectiveProfile> objectiveProfiles, List<ProbabilisticProfile> sourceProfiles,
            BranchWitness witness, List<Map<String, Object>> evidenceRows,
            BIdentificationCatalog.Scene scene, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        List<MetricPoint> points = witness == null
                ? score.rows().stream().map(row -> new MetricPoint(row.xMeters(), row.yMeters())).toList()
                : witness.points();
        var anchorChecks = BIdentificationAcceptance.exactAnchors(sourceProfiles, points, cancellation);
        boolean routeAdmitted = routeAdmitted(points, scene.evidence().decisionRegion());
        var unionChecks = scene.evidence().resolution().nativePitchMeters().isPresent()
                ? BIdentificationAcceptance.geometry(BIdentificationAcceptance.GeometrySelector.PARALLEL_SEGMENTS_X40_80,
                        scene, points, scene.evidence().resolution().nativePitchMeters().getAsDouble(), cancellation, owner)
                : new BIdentificationAcceptance.Checks(List.of());
        BIdentificationAcceptance.Checks branchChecks = new BIdentificationAcceptance.Checks(List.of());
        if (witness != null && scene.evidence().resolution().nativePitchMeters().isPresent()) {
            var original = scene.truth().regions().get(0);
            var branchScene = new BIdentificationCatalog.Scene(scene.evidence(), scene.source(),
                    new BIdentificationCatalog.Truth(List.of(new BIdentificationCatalog.LabelRegion(
                            original.region(), original.structure(), original.raster(),
                            BIdentificationCatalog.AssociationLabel.UNIQUE, List.of(witness.sourceCurve())))));
            branchChecks = BIdentificationAcceptance.geometry(
                    BIdentificationAcceptance.GeometrySelector.PARALLEL_SEGMENTS_X40_80,
                    branchScene, points, scene.evidence().resolution().nativePitchMeters().getAsDouble(),
                    cancellation, owner);
        }
        boolean unionPassed = checkPassed(unionChecks, BIdentificationAcceptance.Code.PARALLEL_SEGMENT_UNION);
        boolean routePassed = points.size() == sourceProfiles.size() && points.size() == 81 && routeAdmitted;
        boolean anchorsPassed = checkPassed(anchorChecks, BIdentificationAcceptance.Code.EXACT_ANCHORS);
        boolean branchPassed = witness != null && checkPassed(branchChecks,
                BIdentificationAcceptance.Code.PARALLEL_SEGMENT_UNION);
        var result = new LinkedHashMap<String, Object>();
        result.put("path", pathId);
        result.put("branch", witness == null ? "RECORDED_MAP" : witness.branchLabel());
        result.put("pointCount", points.size());
        result.put("admittedCellsOnly", true);
        result.put("energy", score.energy());
        result.put("decisionRegionWholeRouteCheck", routeAdmitted ? "PASS" : "FAIL");
        result.put("routeCheck", routePassed ? "PASS" : "FAIL");
        result.put("exactAnchorCheck", anchorsPassed ? "PASS" : "FAIL");
        result.put("namedBranchCheck", witness == null ? "UNAVAILABLE" : checkVerdict(branchChecks,
                BIdentificationAcceptance.Code.PARALLEL_SEGMENT_UNION));
        result.put("parallelSegmentUnionCheck", checkVerdict(unionChecks,
                BIdentificationAcceptance.Code.PARALLEL_SEGMENT_UNION));
        result.put("status", witness == null ? "RECORDED_MAP" : routePassed && anchorsPassed
                && unionPassed && branchPassed ? "AVAILABLE" : "UNAVAILABLE_GEOMETRY_GATE");
        result.put("acceptanceChecks", checksAsMaps(anchorChecks, branchChecks, unionChecks));
        for (var stateRow : stateEvidenceRows(objectiveProfiles, score.rows())) {
            var evidenceRow = new LinkedHashMap<String, Object>(stateRow);
            evidenceRow.put("path", pathId);
            evidenceRows.add(evidenceRow);
        }
        return result;
    }

    private static EvidenceFileReceipt writeObjectiveEvidence(java.nio.file.Path directory,
            List<Map<String, Object>> rows) throws Exception {
        if (directory == null || rows == null || rows.isEmpty() || rows.size() > MAX_OBJECTIVE_EVIDENCE_ROWS)
            throw new IllegalArgumentException("Objective evidence row count is outside its fixed bound");
        var encodedRows = new ArrayList<byte[]>(rows.size());
        long totalBytes = 0;
        for (Map<String, Object> row : rows) {
            if (row == null) throw new IllegalArgumentException("Objective evidence rows cannot be null");
            byte[] canonical = BIdentificationReceipt.canonical(row).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long lineBytes = Math.addExact(canonical.length, 1L);
            if (lineBytes > MAX_OBJECTIVE_EVIDENCE_LINE_BYTES)
                throw new IllegalArgumentException("Objective evidence line exceeds 64 KiB");
            totalBytes = Math.addExact(totalBytes, lineBytes);
            if (totalBytes > MAX_OBJECTIVE_EVIDENCE_TOTAL_BYTES)
                throw new IllegalArgumentException("Objective evidence exceeds its 2 MiB total bound");
            encodedRows.add(canonical);
        }

        java.nio.file.Path temporary = null;
        try {
            temporary = java.nio.file.Files.createTempFile(directory, "objective-evidence-", ".tmp");
            var digest = MessageDigest.getInstance("SHA-256");
            long writtenBytes = 0;
            try (var output = java.nio.file.Files.newOutputStream(temporary)) {
                for (byte[] row : encodedRows) {
                    writtenBytes = Math.addExact(writtenBytes, row.length + 1L);
                    if (writtenBytes > MAX_OBJECTIVE_EVIDENCE_TOTAL_BYTES)
                        throw new IllegalArgumentException("Objective evidence exceeded its 2 MiB write bound");
                    output.write(row);
                    output.write('\n');
                    digest.update(row);
                    digest.update((byte) '\n');
                }
            }
            if (writtenBytes != totalBytes)
                throw new IllegalStateException("Objective evidence byte census changed during serialization");
            java.nio.file.Files.move(temporary, directory.resolve(OBJECTIVE_EVIDENCE_FILE),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            return new EvidenceFileReceipt(rows.size(), writtenBytes, hex(digest.digest()));
        } finally {
            if (temporary != null) java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    private static boolean routeAdmitted(List<MetricPoint> points, MetricRegion region) {
        if (points == null || region == null || points.size() < 2 || points.size() > 4096) return false;
        for (int index = 0; index < points.size(); index++) {
            MetricPoint point = points.get(index);
            if (point == null || !region.contains(point)) return false;
            if (index > 0 && !region.containsSegment(points.get(index - 1), point)) return false;
        }
        return true;
    }

    private static List<Map<String, Object>> stateEvidenceRows(List<ObjectiveProfile> profiles,
            List<MapCostRow> costs) {
        var result = new ArrayList<Map<String, Object>>(costs.size());
        for (MapCostRow cost : costs) {
            ObjectiveProfile profile = profiles.get(cost.index());
            var state = new LinkedHashMap<String, Object>();
            state.put("index", cost.index()); state.put("state", cost.state());
            var components = new ArrayList<Map<String, Object>>(profile.mixture().componentCount());
            for (int componentIndex = 0; componentIndex < profile.mixture().componentCount(); componentIndex++) {
                var component = profile.mixture().component(componentIndex);
                double logDensity = component.logL0DensityAt(cost.state());
                double density = StrictMath.exp(logDensity);
                var fields = new LinkedHashMap<String, Object>();
                fields.put("id", component.id()); fields.put("lineage", component.lineage());
                fields.put("kind", component.kind().name()); fields.put("outcome", component.outcome().name());
                fields.put("massQ", component.mass());
                fields.put("logL0DensityF", Double.isFinite(logDensity) ? logDensity : "ZERO_SUPPORT");
                fields.put("densityF", Double.isFinite(density) ? density : "UNAVAILABLE_OVERFLOW");
                fields.put("responsibility", profile.reduced().responsibility(cost.state(), componentIndex));
                components.add(fields);
            }
            state.put("components", components);
            var orientation = profile.inference().orientationSupport(cost.state());
            state.put("orientation", Map.of("status", orientation.status().name(),
                    "certainty", orientation.certainty(), "reliability", profile.inference().orientationReliability(cost.state()),
                    "modes", orientation.modes().stream().map(mode -> Map.of(
                            "startRadians", mode.startRadians(), "endRadians", mode.endRadians(),
                            "peakBearingRadians", mode.peakBearingRadians(), "response", mode.response())).toList()));
            state.put("originalModeFacts", originalModeFacts(profile.facts()));
            result.add(state);
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> originalModeFacts(BIdentificationFacts.ProfileFacts facts) {
        var result = new ArrayList<Map<String, Object>>(facts.modes().size());
        for (var mode : facts.modes()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("lineage", mode.lineage());
            row.put("background", scalarFact(mode, BIdentificationFacts.Feature.BACKGROUND));
            row.put("rmsDescriptor", scalarFact(mode, BIdentificationFacts.Feature.RMS_DESCRIPTOR));
            row.put("filterDescriptor", scalarFact(mode, BIdentificationFacts.Feature.FILTER_DESCRIPTOR));
            row.put("leftDirectSpan", scalarFact(mode, BIdentificationFacts.Feature.LEFT_DIRECT_SPAN));
            row.put("rightDirectSpan", scalarFact(mode, BIdentificationFacts.Feature.RIGHT_DIRECT_SPAN));
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> scalarFact(BIdentificationFacts.ModeFacts mode,
            BIdentificationFacts.Feature feature) {
        for (var fact : mode.values()) if (fact.feature() == feature) {
            return Map.of("availability", fact.availability().name(),
                    "value", fact.value().isPresent() ? fact.value().getAsDouble() : "UNAVAILABLE",
                    "unit", fact.unit().name());
        }
        return Map.of("availability", "UNAVAILABLE", "value", "UNAVAILABLE", "unit",
                BIdentificationFacts.expectedUnit(feature).name());
    }

    private static boolean checkPassed(BIdentificationAcceptance.Checks checks, BIdentificationAcceptance.Code code) {
        return checks.checks().stream().filter(check -> check.code() == code)
                .allMatch(check -> check.verdict() == BIdentificationAcceptance.Verdict.PASS)
                && checks.checks().stream().anyMatch(check -> check.code() == code);
    }

    private static String checkVerdict(BIdentificationAcceptance.Checks checks, BIdentificationAcceptance.Code code) {
        return checks.checks().stream().filter(check -> check.code() == code).map(check -> check.verdict().name())
                .findFirst().orElse("UNAVAILABLE");
    }

    private static List<Map<String, Object>> checksAsMaps(BIdentificationAcceptance.Checks... groups) {
        var result = new ArrayList<Map<String, Object>>();
        for (var group : groups) for (var check : group.checks()) {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("code", check.code().name()); fields.put("verdict", check.verdict().name());
            fields.put("count", check.count());
            fields.put("observed", check.observed().isPresent() ? check.observed().getAsDouble() : "UNAVAILABLE");
            fields.put("limit", check.limit().isPresent() ? check.limit().getAsDouble() : "UNAVAILABLE");
            result.add(fields);
        }
        return List.copyOf(result);
    }

    private static void appendCostRows(StringBuilder csv, String path, RescoreResult result) {
        for (var row : result.rows()) csv.append(path).append(',').append(row.index()).append(',').append(row.state())
                .append(',').append(row.branchLabel()).append(',').append(row.exactAnchor()).append(',')
                .append(row.mandatory()).append(',').append(row.xMeters()).append(',').append(row.yMeters()).append(',')
                .append(row.unaryEnergy()).append(',').append(row.guideEnergy()).append(',')
                .append(row.pairOrientationEnergy()).append(',').append(row.tripleTurnEnergy()).append(',')
                .append(row.orientationCertainty()).append(',').append(row.orientationReliability()).append('\n');
    }

    private static List<Integer> parseStates(List<?> rawIndices, List<ProbabilisticStateLattice> lattices) {
        var states = new ArrayList<Integer>(rawIndices.size());
        for (int index = 0; index < rawIndices.size(); index++) {
            Object value = rawIndices.get(index);
            if (!(value instanceof Number number)) throw new IllegalArgumentException("Saved MAP state is not numeric");
            int state = number.intValue();
            if (state < 0 || state >= lattices.get(index).cells().size())
                throw new IllegalArgumentException("Saved MAP state is outside the original lattice");
            states.add(state);
        }
        return List.copyOf(states);
    }

    private static RescoreResult rescoreSavedMap(List<InferenceProfile> profiles, List<Integer> states,
            EvidenceModelParameters parameters) {
        if (profiles == null || states == null || parameters == null || profiles.size() < 1
                || profiles.size() != states.size() || profiles.size() > 4096)
            throw new IllegalArgumentException("A bounded saved MAP path is required");
        var rows = new ArrayList<MapCostRow>(profiles.size());
        double energy = originalEnergy("profileEnergy", profiles, 0, states.get(0), parameters);
        rows.add(costRow(profiles, 0, states.get(0), parameters, 0.0, 0.0));
        if (profiles.size() > 1) {
            double first = originalEnergy("profileEnergy", profiles, 0, states.get(0), parameters);
            double second = originalEnergy("profileEnergy", profiles, 1, states.get(1), parameters);
            double pair = originalEnergy("pairEnergy", profiles, 0, states.get(0), states.get(1), parameters);
            energy = first + second + pair;
            rows.set(0, costRow(profiles, 0, states.get(0), parameters, 0.0, 0.0));
            rows.add(costRow(profiles, 1, states.get(1), parameters, pair, 0.0));
        }
        for (int index = 2; index < profiles.size(); index++) {
            double unaryAndGuide = originalEnergy("profileEnergy", profiles, index, states.get(index), parameters);
            double pair = originalEnergy("pairEnergy", profiles, index - 1, states.get(index - 1), states.get(index), parameters);
            double triple = originalEnergy("tripleEnergy", profiles, index, states.get(index - 2),
                    states.get(index - 1), states.get(index), parameters);
            double increment = unaryAndGuide + pair + triple;
            energy += increment;
            rows.add(costRow(profiles, index, states.get(index), parameters, pair, triple));
        }
        return new RescoreResult(energy, rows);
    }

    private static MapCostRow costRow(List<InferenceProfile> profiles, int index, int state,
            EvidenceModelParameters parameters, double pair, double triple) {
        var profile = profiles.get(index);
        var cell = profile.cells().get(state);
        double quadrature = profileQuadrature(profiles, index);
        double unary = quadrature * parameters.dataWeight() * profile.unaryCost(state);
        double guide = quadrature * parameters.guideWeight() * profile.structuralGuideCost(state);
        MetricPoint point = profile.point(state);
        var orientation = profile.orientationSupport(state);
        return new MapCostRow(index, state, cell.branchLabel(), cell.exactAnchor(), cell.mandatory(),
                point.xMeters(), point.yMeters(), unary, guide, pair, triple, orientation.certainty(),
                profile.orientationReliability(state));
    }

    private static double profileQuadrature(List<InferenceProfile> profiles, int index) {
        if (profiles.size() == 1) return 1.0;
        if (index == 0) return .5 * (profiles.get(1).chainageMeters() - profiles.get(0).chainageMeters());
        if (index == profiles.size() - 1) return .5 * (profiles.get(index).chainageMeters()
                - profiles.get(index - 1).chainageMeters());
        return .5 * (profiles.get(index + 1).chainageMeters() - profiles.get(index - 1).chainageMeters());
    }

    private static double originalEnergy(String name, Object... arguments) {
        Class<?>[] signature = name.equals("profileEnergy")
                ? new Class<?>[] {List.class, int.class, int.class, EvidenceModelParameters.class}
                : name.equals("pairEnergy")
                        ? new Class<?>[] {List.class, int.class, int.class, int.class, EvidenceModelParameters.class}
                        : new Class<?>[] {List.class, int.class, int.class, int.class, int.class,
                                EvidenceModelParameters.class};
        try {
            Method method = ProbabilisticInference.class.getDeclaredMethod(name, signature);
            method.setAccessible(true);
            return (double) method.invoke(null, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Original inference objective method is unavailable: " + name, failure);
        }
    }

    private static List<BranchWitness> buildBranchWitnesses(List<InferenceProfile> profiles,
            List<ProbabilisticStateLattice> lattices, List<Integer> savedStates,
            List<List<MetricPoint>> sourceBranches) {
        if (profiles == null || lattices == null || savedStates == null || sourceBranches == null
                || profiles.size() != 81 || profiles.size() != lattices.size() || profiles.size() != savedStates.size()
                || sourceBranches.size() != 2 || sourceBranches.get(0) == null || sourceBranches.get(1) == null
                || sourceBranches.get(0).equals(sourceBranches.get(1)))
            return List.of();
        var result = new ArrayList<BranchWitness>(2);
        String[] names = {"lower-branch", "upper-branch"};
        for (int branch = 0; branch < sourceBranches.size(); branch++) {
            List<MetricPoint> source = sourceBranches.get(branch);
            if (source == null || source.size() < 2) return List.of();
            var states = new ArrayList<Integer>(profiles.size());
            var points = new ArrayList<MetricPoint>(profiles.size());
            for (int index = 0; index < profiles.size(); index++) {
                var profile = profiles.get(index);
                var lattice = lattices.get(index);
                if (lattice.cells().size() != profile.cells().size()) return List.of();
                int saved = savedStates.get(index);
                if (saved < 0 || saved >= lattice.cells().size()) return List.of();
                if (!Double.isFinite(interpolateY(source, profile.point(saved).xMeters()))) return List.of();
                int selected = saved;
                int exact = -1;
                for (int state = 0; state < lattice.cells().size(); state++)
                    if (lattice.cells().get(state).exactAnchor()) {
                        if (exact >= 0) return List.of();
                        exact = state;
                    }
                if (exact >= 0) selected = exact;
                else {
                    double best = Double.POSITIVE_INFINITY;
                    boolean targetAvailable = false;
                    for (int state = 0; state < lattice.cells().size(); state++) {
                        MetricPoint candidate = profile.point(state);
                        double target = interpolateY(source, candidate.xMeters());
                        if (!Double.isFinite(target)) continue;
                        targetAvailable = true;
                        double residual = Math.abs(candidate.yMeters() - target);
                        if (residual < best) { best = residual; selected = state; }
                    }
                    if (!targetAvailable) return List.of();
                }
                states.add(selected);
                points.add(profile.point(selected));
            }
            result.add(new BranchWitness(names[branch], states, points, source));
        }
        return List.copyOf(result);
    }

    private static double interpolateY(List<MetricPoint> curve, double x) {
        for (int index = 1; index < curve.size(); index++) {
            MetricPoint left = curve.get(index - 1), right = curve.get(index);
            double minimum = Math.min(left.xMeters(), right.xMeters());
            double maximum = Math.max(left.xMeters(), right.xMeters());
            if (x < minimum || x > maximum) continue;
            if (left.xMeters() == right.xMeters()) return .5 * (left.yMeters() + right.yMeters());
            return left.yMeters() + (right.yMeters() - left.yMeters())
                    * (x - left.xMeters()) / (right.xMeters() - left.xMeters());
        }
        return Double.NaN;
    }

    static java.nio.file.Path prepareOutputDirectory(java.nio.file.Path parent) throws java.io.IOException {
        java.nio.file.Files.createDirectories(parent);
        return java.nio.file.Files.createDirectory(parent.resolve("receipt"));
    }
}
