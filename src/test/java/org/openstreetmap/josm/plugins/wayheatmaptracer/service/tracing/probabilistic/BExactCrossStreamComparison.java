package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Objects;
import java.util.HexFormat;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Separate fixed-public raw-one comparison, never an inference implementation. */
final class BExactCrossStreamComparison {
    static final List<String> IDS = List.of("switchback-thin-strong", "switchback-thin-faint",
            "same-trail-three-strong", "same-trail-five-strong", "same-trail-three-separated-strong");
    static final String IDENTITY = "IDENTIFIED_B0_EXACT_CROSS_STREAM_RAW1_V3_NOT_NATIVE_DEFAULT_OR_MODEL_QUALIFICATION";
    static final String GRAPH_FORMAT = "cross-stream-graph-big-endian-raw-binary64-v1";
    static final long MEMORY = 64L * 1024 * 1024, CAPTURE_BYTES = 32L * 1024 * 1024, METADATA_BYTES = 1024L * 1024;
    static final int LEAF_BYTES = 65_536, MAX_CHUNKS = 128;
    static final EvidenceModelParameters PARAMETERS = EvidenceModelParameters.defaults();
    static final BObservationKernel B0 = new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1, 1, 0));
    static final TraceBudgets BUDGETS = new TraceBudgets(96, 8_000_000, 128_000_000, 1, 1);
    private final Controls controls;
    private long lastClosedBytes = -1;
    private String lastClosedCase;

    BExactCrossStreamComparison(Controls controls) { this.controls = Objects.requireNonNull(controls); }
    long lastClosedBytes() { return lastClosedBytes; }
    String lastClosedCase() { return lastClosedCase; }

    /** Independently pinned old input facts, not an old successful numerical output. */
    record FrozenInput(String snapshotHash, String factsAndLatticeHash, int profiles, int modes,
            long originalPairInventory, long observedForwardBackwardScores, String archiveSha256, String memberSha256) {
        FrozenInput {
            for (String value : List.of(snapshotHash, factsAndLatticeHash, archiveSha256, memberSha256)) sha(value);
            if (profiles != 57 || modes < 0 || originalPairInventory <= 0 || observedForwardBackwardScores <= 0)
                throw new IllegalArgumentException("Fixed archived physical input inventory required");
        }
    }

    /** Source closure is authenticated upstream; actual publication may be a later metadata-only commit. */
    record Controls(boolean approved, String reviewedCodeCommit, String actualCheckoutCommit,
            String sourceClosureSha256, String protocolSha256, Map<String, FrozenInput> baseline) {
        Controls {
            commit(reviewedCodeCommit); commit(actualCheckoutCommit); sha(sourceClosureSha256); sha(protocolSha256);
            if (baseline == null || !baseline.keySet().equals(Set.copyOf(IDS)) || baseline.values().stream().anyMatch(Objects::isNull))
                throw new SecurityException("All five independently pinned archived inputs required");
            baseline = Map.copyOf(baseline);
        }
    }

    static Controls loadProtocol(Path file, String expectedSha256, String actualCheckout) throws Exception {
        sha(expectedSha256); commit(actualCheckout);
        byte[] bytes = boundedRead(file);
        if (!BIdentificationReceipt.hash(bytes).equals(expectedSha256)) throw new SecurityException("Protocol pin changed");
        var value = BIdentificationReceipt.parse(bytes);
        if (!Boolean.TRUE.equals(value.get("approval")) || !Boolean.TRUE.equals(value.get("engineEligible")))
            throw new SecurityException("Pending protocol cannot execute");
        if (!IDS.equals(value.get("cases")) || !IDENTITY.equals(value.get("comparisonIdentity"))
                || !ProbabilisticExecutionPolicy.CROSS_STREAM_VERSION.equals(value.get("executionPolicy")))
            throw new SecurityException("Fixed comparison policy changed");
        var limits = map(value.get("limits"));
        if (integer(limits.get("states")) != 96 || integer(limits.get("pairs")) != 8_000_000
                || integer(limits.get("transitions")) != 128_000_000 || integer(limits.get("raw")) != 1
                || integer(limits.get("distinct")) != 1 || integer(limits.get("bytes")) != MEMORY
                || integer(limits.get("cooperativeSeconds")) != 300 || integer(limits.get("externalSeconds")) != 360
                || !Boolean.TRUE.equals(value.get("nullPotential")) || integer(value.get("eta")) != 0)
            throw new SecurityException("Fixed comparison limits changed");
        var pins = new LinkedHashMap<String, FrozenInput>();
        for (Object item : list(value.get("baselineInputs"))) {
            var row = map(item); String id = text(row.get("case")); requireCase(id);
            if (pins.put(id, new FrozenInput(text(row.get("snapshotHash")), text(row.get("factsAndLatticeHash")),
                    Math.toIntExact(integer(row.get("profiles"))), Math.toIntExact(integer(row.get("modes"))),
                    integer(row.get("originalPairInventory")), integer(row.get("observedForwardBackwardScores")),
                    text(row.get("archiveSha256")), text(row.get("memberSha256")))) != null)
                throw new SecurityException("Duplicate baseline case");
            if (!Boolean.FALSE.equals(row.get("selectedAdmissionCoarsened")) || !Boolean.TRUE.equals(row.get("fullTwentyFiveMetreDomain")))
                throw new SecurityException("Archived admission/domain changed");
        }
        return new Controls(true, text(value.get("reviewedCodeCommit")), actualCheckout,
                text(value.get("sourceClosureSha256")), expectedSha256, pins);
    }

    static void requireCase(String id) { if (!IDS.contains(id)) throw new SecurityException("Fixed public case required"); }

    /** Runs one unchanged factual assembly and exactly one funded V3 call; no baseline/G solve. */
    Map<String, Object> execute(String id, Path output, LongSupplier clock, CancellationProbe cancellation) throws Exception {
        requireCase(id); Objects.requireNonNull(output); Objects.requireNonNull(clock); Objects.requireNonNull(cancellation);
        if (!controls.approved()) throw new SecurityException("Pending controls cannot acquire a source");
        if (Files.isSymbolicLink(output)) throw new SecurityException("Output directory must be owned");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output); // Atomic fresh case namespace; never reuse unrelated files or logs.
        var ledger = new AttemptMemoryLedger(MEMORY);
        try (var publicationOwner = ledger.rootOwner(); var metadata = publicationOwner.reserve(METADATA_BYTES)) {
        var state = new Run(id, controls);
        // CREATE_NEW is the no-retry claim. The phase file is ours only after successful creation.
        Path phase = output.resolve("phase.json");
        writeInitialPhase(phase, state.phase());
        long started = clock.getAsLong();
        CancellationProbe probe = () -> {
            if (state.expectEntry && !state.entryObserved) state.entryObserved = StackWalker.getInstance().walk(frames -> frames.anyMatch(
                    frame -> frame.getClassName().equals(ProbabilisticInference.class.getName()) && frame.getMethodName().equals("solveCrossStreamRaw1")));
            boolean timedOut = clock.getAsLong() - started >= 300_000_000_000L;
            state.deadlineExpired |= timedOut;
            return cancellation.cancelled() || timedOut;
        };
        var receipt = state.receipt;
        var routeLeaves = new LinkedHashMap<String, Object>();
        try (var owner = publicationOwner.child("source-and-inference"); var render = owner.reserve(CAPTURE_BYTES)) {
            state.phase = "CAPTURE"; updatePhase(phase, state); probe.checkpoint();
            var source = V022BGapMatchedControlsTest.nativeComparisonSource(id);
            var capture = new LinkedHashMap<String, Object>();
            var evidence = V022BGapMatchedControlsTest.captureNativeComparisonSource(id, capture, probe);
            receipt.put("capture", capture); receipt.put("snapshotHash", evidence.canonicalHash());
            receipt.put("sourceBits", pointBits(source)); receipt.put("source", points(source));
            receipt.put("sourceIdentity", evidence.sourceIdentity()); receipt.put("frame", boundedText(evidence.coordinateFrame().toString()));
            receipt.put("transform", boundedText(evidence.transform().toString())); receipt.put("resampling", boundedText(evidence.resampling().toString()));
            receipt.put("frameBits", frameBits(evidence));
            var frozen = controls.baseline().get(id);
            if (!evidence.canonicalHash().equals(frozen.snapshotHash())) throw new SecurityException("Frozen snapshot differs");
            state.phase = "PROFILES"; updatePhase(phase, state);
            var factory = new ProbabilisticProfileFactory();
            var raw = factory.create(source, factory.profileChainage(source, 1.5), 25, true, evidence,
                    evidence.fields().get("scalar"), PARAMETERS, probe, true, owner);
            int modes = 0; for (var row : raw) { probe.checkpoint(); modes = Math.addExact(modes, row.modes().size()); }
            receipt.put("profiles", raw.size()); receipt.put("modes", modes);
            if (raw.size() != frozen.profiles() || modes != frozen.modes()) throw new SecurityException("Frozen profile inventory differs");
            state.phase = "FACTS"; updatePhase(phase, state);
            var facts = new BIdentificationFactsExtractor().extract(evidence, raw, probe, owner);
            var proposals = ProbabilisticContinuationProposals.build(raw, probe, owner, PARAMETERS.localization());
            state.phase = "ADMISSION"; updatePhase(phase, state);
            try (var admission = ProbabilisticOptionalOverflowAdmission.select(raw, proposals, TraceBudgets.defaults(), probe, owner)) {
                receipt.put("admissionPolicy", ProbabilisticOptionalOverflowAdmission.POLICY_ID);
                receipt.put("admissionAdmitted", admission.admitted()); receipt.put("admissionCoarsened", admission.coarsened());
                receipt.put("admissionRefusalReason", boundedText(admission.refusalReason()));
                if (!admission.admitted() || admission.coarsened()) throw new SecurityException("Frozen uncoarsened admission unavailable");
                var profiles = new ArrayList<InferenceProfile>(); var lattices = new ArrayList<ProbabilisticStateLattice>();
                var decisions = new ArrayList<Map<String, Object>>(); long pairs = 0; boolean full = true;
                for (int row = 0; row < raw.size(); row++) {
                    probe.checkpoint(); var lattice = admission.buildProfile(row, owner, probe).lattice().orElseThrow();
                    lattices.add(lattice);
                    if (row > 0) pairs = Math.addExact(pairs, Math.multiplyExact((long) lattices.get(row - 1).cells().size(), lattice.cells().size()));
                    if (row > 0 && row + 1 < raw.size()) full &= lattice.cells().stream().mapToDouble(LateralStateCell::offsetMeters).min().orElseThrow() == -25
                            && lattice.cells().stream().mapToDouble(LateralStateCell::offsetMeters).max().orElseThrow() == 25;
                    decisions.add(Map.of("row", row, "declinedOptional", admission.declined(row),
                            "originalProposalBits", bits(admission.originalProposal(row)), "effectiveProposalBits", bits(admission.effectiveProposal(row))));
                    profiles.add(BObservationAdapter.evaluate(facts.get(row), lattice, PARAMETERS, true, B0, probe, owner));
                }
                String factsHash = BIdentificationReceipt.factsHash(facts, lattices, probe);
                receipt.put("factsAndLatticeHash", factsHash); receipt.put("originalPairInventory", pairs);
                receipt.put("factsEvidence", "HASH_ONLY_EXISTING_FROZEN_FACTS_HASH; COMPLETE_NUMERICAL_PROFILE_BITS_RETAINED_IN_GRAPH_CHUNKS");
                receipt.put("fullTwentyFiveMetreDomain", full); receipt.put("admissionRows", decisions);
                receipt.put("admissionUnits", admission.work().units); receipt.put("admissionWorkUpper", admission.work().upper);
                if (!full || pairs != frozen.originalPairInventory() || !factsHash.equals(frozen.factsAndLatticeHash()))
                    throw new SecurityException("Frozen physical graph differs");
                state.phase = "GRAPH_EVIDENCE"; updatePhase(phase, state);
                receipt.put("graph", graph(id, profiles, output, probe));
                state.phase = "BEFORE_FUNDED_SOLVE"; updatePhase(phase, state); probe.checkpoint();
                state.expectEntry = true; state.invocationAttempted = true; updatePhase(phase, state);
                var result = new ProbabilisticInference().solveCrossStreamRaw1(profiles, PARAMETERS, BUDGETS,
                        evidence.decisionRegion(), probe, owner, null);
                state.normalReturns = 1; state.phase = "RETURNED_FUNDED_SOLVE"; updatePhase(phase, state);
                receipt.put("solver", resultReceipt(result));
                var posteriors = posteriorLeaves(id, result.result(), probe);
                var posteriorManifest = new ArrayList<Map<String, Object>>();
                for (var entry : posteriors.entrySet()) posteriorManifest.add(Map.of("file", entry.getKey(), "bytes", entry.getValue().length,
                        "sha256", BIdentificationReceipt.hash(entry.getValue())));
                receipt.put("posteriorFiles", posteriorManifest); receipt.put("posteriorDataAvailable", true);
                receipt.put("posteriorFormat", "CSP1:i32magic,kind:utf,rowCount:i32,(rowLength:i32,binary64RawBits:u64*rowLength)*rowCount;BIG_ENDIAN");
                receipt.put("originalFullForwardBackwardScores", frozen.observedForwardBackwardScores());
                if (result.result().workAccounting().isPresent()) {
                    var work = result.result().workAccounting().orElseThrow();
                    boolean completedFb = work.kBestScores() > 0 || result.result().completion().isPresent();
                    receipt.put("fullForwardBackwardComparison", completedFb ? "AVAILABLE" : "UNAVAILABLE_FULL_FB_NOT_COMPLETED");
                    if (completedFb && work.forwardBackwardScores() != frozen.observedForwardBackwardScores())
                        throw new SecurityException("Original full forward-backward work differs");
                }
                var paths = routes(result.result().rawPaths());
                if (paths.size() > 1) throw new SecurityException("Raw-one output exceeds unchanged cap");
                for (int index = 0; index < paths.size(); index++) {
                    var route = new LinkedHashMap<>(paths.get(index));
                    route.put("truthOracle", truth(id, source, result.result(), index, probe, owner));
                    routeLeaves.put("route-" + id + "-" + index + ".json", route);
                }
                routeLeaves.putAll(posteriors);
                receipt.put("returnedRouteCount", paths.size());
                receipt.put("routeDataAvailable", !paths.isEmpty() && usable(result.result()));
                receipt.put("geometryDisposition", paths.isEmpty() || !usable(result.result()) ? "UNAVAILABLE_NO_COMPLETE_RETURNED_PATH" : "SEE_EACH_UNCHANGED_TRUTH_ORACLE");
                state.phase = "COPY_COMPLETE";
            }
        } catch (Exception failure) {
            state.fail(failure); routeLeaves.clear();
        } finally {
            receipt.put("peakBytes", ledger.peakBytes()); receipt.put("limitBytes", ledger.limitBytes());
            receipt.put("remainingNonMetadataBytesAtPublication", ledger.currentBytes() - METADATA_BYTES);
            receipt.put("metadataBytesStillFundedAtPublication", METADATA_BYTES);
            receipt.put("outerFinallyRequiresWholeLedgerZero", true);
        }
        if (ledger.currentBytes() != METADATA_BYTES) throw new IllegalStateException("Non-metadata attempt ownership did not close");
        state.terminal = true;
        receipt.put("actualSolverEntries", state.entryObserved ? 1 : 0); receipt.put("normalReturns", state.normalReturns);
        receipt.put("invocationAttempted", state.invocationAttempted); receipt.put("entryObservation", "SAME_PROBE_IN_ACTUAL_FUNDED_ENTRY");
        receipt.put("pipelineComplete", state.failure == null); receipt.put("deadlineExpired", state.deadlineExpired);
        receipt.put("elapsedBeforePublicationNanos", clock.getAsLong() - started);
        receipt.put("timingScope", "NEW_ATTEMPT_CAPTURE_THROUGH_COPY; NO_COMPARABLE_OLD_SUCCESS_OR_CPU_SPEEDUP_CLAIM");
        receipt.put("failurePhase", state.failurePhase); receipt.put("safeCause", state.failure);
        var batch = new LinkedHashMap<String, Object>(routeLeaves);
        receipt.put("routeFiles", routeLeaves.keySet().stream().filter(name -> name.startsWith("route-")).toList()); batch.put(id + ".json", receipt);
        batch.put("summary.json", Map.of("expectedCases", IDS, "recordedCases", List.of(id), "comparisonIdentity", IDENTITY,
                "modelQualified", false, "thisAttemptPipelineComplete", state.failure == null));
        state.phase = "RESULT_PUBLICATION";
        try { publish(output, batch, state.failure == null ? probe : CancellationProbe.NONE); }
        catch (Exception failure) {
            state.fail(failure); receipt.put("pipelineComplete", false); receipt.put("routeFiles", List.of());
            receipt.put("posteriorFiles", List.of()); receipt.put("posteriorDataAvailable", false);
            receipt.put("routeDataAvailable", false); receipt.put("geometryDisposition", "UNAVAILABLE_PUBLICATION_FAILURE");
            receipt.put("failurePhase", state.failurePhase); receipt.put("safeCause", state.failure);
            publish(output, Map.of(id + ".json", receipt, "summary.json", Map.of("expectedCases", IDS, "recordedCases", List.of(id),
                    "comparisonIdentity", IDENTITY, "modelQualified", false, "thisAttemptPipelineComplete", false)), CancellationProbe.NONE);
        }
        state.phase = state.failure == null ? "TERMINAL_BOUNDED_ATTEMPT" : "TERMINAL_PIPELINE_FAILURE";
        updatePhase(phase, state); return receipt;
        } finally {
            lastClosedBytes = ledger.currentBytes(); lastClosedCase = id;
            if (lastClosedBytes != 0) throw new IllegalStateException("Whole attempt ownership did not close");
        }
    }

    private static final class Run {
        final String id; final Controls controls; final Map<String, Object> receipt = new LinkedHashMap<>();
        String phase = "CLAIMED_BEFORE_ACQUISITION", failure, failurePhase;
        boolean expectEntry, entryObserved, invocationAttempted, deadlineExpired, terminal; int normalReturns;
        Run(String id, Controls controls) {
            this.id = id; this.controls = controls;
            receipt.put("case", id); receipt.put("comparisonIdentity", IDENTITY); receipt.put("reviewedCodeCommit", controls.reviewedCodeCommit());
            receipt.put("actualCheckoutCommit", controls.actualCheckoutCommit()); receipt.put("sourceClosureSha256", controls.sourceClosureSha256());
            receipt.put("protocolSha256", controls.protocolSha256()); receipt.put("sourceAuthentication", "UPSTREAM_REVIEWED_WORKFLOW_REQUIRED");
            receipt.put("eta", 0); receipt.put("potential", "NULL"); receipt.put("parameterDiagnosticSha256",
                    BIdentificationReceipt.hash(PARAMETERS.toString().getBytes(StandardCharsets.UTF_8)));
            receipt.put("B0", List.of(1, 1, 0)); receipt.put("sourceSamplingPolicy", EvidenceModelParameters.profileSamplingPolicyVersion());
            receipt.put("modelQualified", false); receipt.put("routeDataAvailable", false); receipt.put("returnedRouteCount", null);
        }
        Map<String, Object> phase() {
            var result = new LinkedHashMap<String, Object>(); result.put("case", id); result.put("phase", phase);
            result.put("terminal", terminal); result.put("protocolSha256", controls.protocolSha256());
            Integer observedEntries = null, observedReturns = null;
            if (entryObserved) observedEntries = 1;
            else if (terminal) observedEntries = 0;
            if (normalReturns > 0) observedReturns = normalReturns;
            else if (terminal) observedReturns = 0;
            result.put("invocationAttempted", invocationAttempted); result.put("actualSolverEntries", observedEntries);
            result.put("normalReturns", observedReturns);
            result.put("externalCutoffEntryCertainty", "UNKNOWN_UNLESS_SAME_PROBE_ENTRY_OR_NORMAL_RETURN_OBSERVED");
            return result;
        }
        void fail(Throwable failure) {
            this.failurePhase = phase;
            this.failure = failure instanceof CancellationException ? "CANCELLED_OR_DEADLINE"
                    : failure instanceof TraceMemoryLimitException || failure instanceof AttemptMemoryLedger.ResourceLimitException ? "RESOURCE_LIMIT"
                    : failure instanceof ArithmeticException ? "NUMERIC_FAILURE" : failure instanceof SecurityException ? "INPUT_BINDING_REFUSAL"
                    : failure instanceof IOException || failure instanceof UncheckedIOException ? "IO_FAILURE"
                    : failure instanceof ExportOverflow ? "EXPORT_OVERFLOW" : "RUNTIME_FAILURE";
            receipt.put("routeDataAvailable", false); receipt.put("geometryDisposition", "UNAVAILABLE_PIPELINE_FAILURE");
            receipt.put("posteriorFiles", List.of()); receipt.put("posteriorDataAvailable", false);
            if (failure instanceof TraceMemoryLimitException limited) receipt.put("typedMemoryAbort", Map.of("stage", limited.stage(),
                    "pairVisits", limited.pairVisits(), "transitions", limited.transitions(), "currentBytes", limited.currentBytes(),
                    "peakBytes", limited.peakBytes(), "limitBytes", limited.limitBytes()));
        }
    }

    static boolean usable(ProbabilisticInferenceResult result) {
        return result != null && result.completion().isPresent() && result.posteriorUsable() && !result.rawPaths().isEmpty()
                && result.status() != ProbabilisticInferenceResult.Status.RESOURCE_LIMIT
                && result.status() != ProbabilisticInferenceResult.Status.NUMERIC_FAILURE && result.status() != ProbabilisticInferenceResult.Status.NO_ROUTE;
    }

    static Map<String, Object> truth(String id, List<MetricPoint> source, ProbabilisticInferenceResult result, int route,
            CancellationProbe probe, AttemptMemoryLedger.Owner owner) {
        requireCase(id);
        if (!usable(result)) return Map.of("available", false, "passed", false, "availability", "UNAVAILABLE_NO_COMPLETE_RETURNED_PATH");
        if (!pointBits(source).equals(pointBits(BThinSwitchbackFixtures.source())))
            throw new SecurityException("Original preservation reference differs");
        var value = new LinkedHashMap<String, Object>(BThinSwitchbackAcceptance.evaluate(BThinSwitchbackFixtures.source(),
                result.rawPaths().get(route).points(), 1, probe, owner).receipt());
        value.put("referenceIdentity", "ORIGINAL_FROZEN_METRIC_SOURCE_PRESERVATION_REFERENCE"); return value;
    }

    /** Complete graph evidence, including every orientation mode and responsibility, in bounded binary cells. */
    static Map<String, Object> graph(String id, List<InferenceProfile> profiles, Path output, CancellationProbe probe) throws Exception {
        requireCase(id); Objects.requireNonNull(probe);
        if (profiles == null || profiles.isEmpty() || profiles.size() > 81) throw new ExportOverflow();
        if (Files.isSymbolicLink(output)) throw new SecurityException("Graph output cannot be a symlink");
        Files.createDirectories(output);
        Path stage = Files.createTempDirectory(output, ".owned-graph-");
        var names = new ArrayList<String>(); var chunks = new ArrayList<Map<String, Object>>();
        var digest = MessageDigest.getInstance("SHA-256");
        var cell = new Buffer(); var chunk = new Buffer();
        var cellOut = new DataOutputStream(cell); var chunkOut = new DataOutputStream(chunk);
        int inChunk = 0, totalCells = 0; long fieldWrites = 0, bytes = 0;
        chunkOut.writeInt(0x43534731); chunkOut.writeInt(0);
        try {
            for (int row = 0; row < profiles.size(); row++) {
                var profile = profiles.get(row);
                if (profile.cells().isEmpty() || profile.cells().size() > 96) throw new ExportOverflow();
                for (int state = 0; state < profile.cells().size(); state++) {
                    probe.checkpoint(); cell.reset();
                    var lateral = profile.cells().get(state); var point = profile.point(state);
                    cellOut.writeInt(row); cellOut.writeInt(state);
                    for (double value : new double[] {profile.chainageMeters(), profile.anchor().xMeters(), profile.anchor().yMeters(),
                            profile.normalUnit().xMeters(), profile.normalUnit().yMeters(), lateral.offsetMeters(), lateral.quadratureWidthMeters(),
                            point.xMeters(), point.yMeters(), profile.unaryCost(state), profile.structuralGuideCost(state), profile.orientationReliability(state)})
                        cellOut.writeLong(Double.doubleToRawLongBits(value));
                    fieldWrites = Math.addExact(fieldWrites, 14);
                    cellOut.writeByte((lateral.exactAnchor() ? 1 : 0) | (lateral.mandatory() ? 2 : 0));
                    cellOut.writeUTF(profile.ownership().name()); cellOut.writeBoolean(profile.entirelyMissing());
                    cellOut.writeUTF(boundedText(lateral.branchLabel()));
                    var orientation = profile.orientationSupport(state);
                    cellOut.writeUTF(orientation.status().name()); cellOut.writeLong(Double.doubleToRawLongBits(orientation.certainty()));
                    cellOut.writeInt(orientation.modes().size()); fieldWrites = Math.addExact(fieldWrites, 7);
                    for (var mode : orientation.modes()) {
                        probe.checkpoint();
                        cellOut.writeLong(Double.doubleToRawLongBits(mode.startRadians())); cellOut.writeLong(Double.doubleToRawLongBits(mode.endRadians()));
                        cellOut.writeLong(Double.doubleToRawLongBits(mode.peakBearingRadians())); cellOut.writeLong(Double.doubleToRawLongBits(mode.response()));
                        fieldWrites = Math.addExact(fieldWrites, 4);
                    }
                    cellOut.writeInt(profile.componentCount()); fieldWrites = Math.addExact(fieldWrites, 1);
                    for (int component = 0; component < profile.componentCount(); component++) {
                        probe.checkpoint(); cellOut.writeLong(Double.doubleToRawLongBits(profile.componentResponsibility(state, component)));
                        fieldWrites = Math.addExact(fieldWrites, 1);
                    }
                    if (cell.size() + 12 > LEAF_BYTES) throw new ExportOverflow();
                    if (inChunk == 64 || chunk.size() + 4 + cell.size() > LEAF_BYTES) {
                        bytes = Math.addExact(bytes, flushGraph(id, stage, names, chunks, chunk, inChunk, digest));
                        chunk.reset(); chunkOut.writeInt(0x43534731); chunkOut.writeInt(0); inChunk = 0;
                    }
                    chunkOut.writeInt(cell.size()); cell.writeTo(chunk); inChunk++; totalCells++;
                }
            }
            if (inChunk > 0) bytes = Math.addExact(bytes, flushGraph(id, stage, names, chunks, chunk, inChunk, digest));
            var manifest = new LinkedHashMap<String, Object>(); manifest.put("format", GRAPH_FORMAT); manifest.put("byteOrder", "BIG_ENDIAN");
            manifest.put("profiles", profiles.size()); manifest.put("cells", totalCells); manifest.put("chunks", chunks);
            manifest.put("completeCellSequence", "PROFILE_ORDER_THEN_STATE_ORDER; NO_OMITTED_ORIENTATION_OR_RESPONSIBILITY_FIELDS");
            manifest.put("cellLayout", "row:i32,state:i32,chainage/anchorXY/normalXY/offset/width/pointXY/unary/guide/reliability:12*u64raw,flags:u8,ownership:utf,missing:bool,branch:utf,orientationStatus:utf,certainty:u64raw,modeCount:i32,modes:4*u64raw,componentCount:i32,responsibilities:u64raw");
            manifest.put("chunkLayout", "magic:CSG1,count:i32,(cellByteLength:i32,cellBytes)*count; <=64cells");
            manifest.put("graphBytesSha256", HexFormat.of().formatHex(digest.digest())); manifest.put("retainedBytes", bytes);
            manifest.put("fieldWrites", fieldWrites); manifest.put("fieldWorkUnits", "ACTUAL_TYPED_FIELD_WRITES_NOT_SOLVER_SCORES_OR_CPU_INSTRUCTIONS");
            String name = "graph-manifest-" + id + ".json"; writeNew(stage.resolve(name), encode(manifest)); names.add(name);
            commitStage(stage, output, names, probe); return manifest;
        } catch (Exception | Error failure) { cleanup(stage, names, List.of(), output, failure); throw failure; }
    }

    private static long flushGraph(String id, Path stage, List<String> names, List<Map<String, Object>> chunks,
            Buffer chunk, int cells, MessageDigest digest) throws Exception {
        if (chunks.size() >= MAX_CHUNKS || cells < 1 || cells > 64) throw new ExportOverflow();
        chunk.putInt(4, cells); byte[] bytes = chunk.toByteArray();
        String name = "graph-" + id + "-" + String.format(java.util.Locale.ROOT, "%05d", chunks.size()) + ".bin";
        writeNew(stage.resolve(name), bytes); names.add(name); digest.update(bytes);
        chunks.add(Map.of("file", name, "cells", cells, "bytes", bytes.length, "sha256", BIdentificationReceipt.hash(bytes)));
        return bytes.length;
    }

    /** Fixed64KiB backing storage; bounds are checked BEFORE ByteArrayOutputStream can grow. */
    private static final class Buffer extends ByteArrayOutputStream {
        Buffer() { super(LEAF_BYTES); }
        @Override public synchronized void write(int value) { if (count == LEAF_BYTES) throw new ExportOverflow(); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length < 0 || count > LEAF_BYTES - length) throw new ExportOverflow(); super.write(bytes, offset, length);
        }
        void putInt(int offset, int value) {
            if (offset < 0 || offset + 4 > count) throw new ExportOverflow();
            for (int i = 3; i >= 0; i--) buf[offset + 3 - i] = (byte) (value >>> (8 * i));
        }
    }

    static Map<String, Object> resultReceipt(CrossStreamInferenceResult wrapped) {
        var result = wrapped.result(); var value = new LinkedHashMap<String, Object>();
        value.put("status", result.status().name()); value.put("posteriorUsable", result.posteriorUsable());
        value.put("logPartitionBits", bits(result.logPartition())); value.put("alternativeSearchTruncated", result.alternativeSearchTruncated());
        value.put("rawCount", result.rawPaths().size()); value.put("distinctCount", result.distinctPaths().size());
        value.put("pairVisits", result.evaluatedPairVisits()); value.put("transitions", result.evaluatedTransitions());
        value.put("completion", result.completion().map(c -> (Object) Map.of("rawLimit", c.effectiveRawLimit(), "distinctLimit", c.effectiveDistinctLimit(),
                "terminalCount", c.completePathsAtSaturation(), "terminalSaturated", c.terminalCountSaturated(), "rawEnumerationCapped", c.rawEnumerationCapped(),
                "requestedDiversityReached", c.requestedDiversityReached())).orElse(null));
        value.put("work", result.workAccounting().map(w -> (Object) Map.of("executionPolicy", w.executionPolicy(), "forwardBackwardScores", w.forwardBackwardScores(),
                "kBestScores", w.kBestScores(), "logicalKBestExtensions", w.logicalKBestExtensions(), "refusedScoreAttempts", w.refusedScoreAttempts())).orElse(null));
        var metrics = new LinkedHashMap<String, Object>();
        try { for (var component : CrossStreamWorkAccounting.class.getRecordComponents()) metrics.put(component.getName(), component.getAccessor().invoke(wrapped.boundWork())); }
        catch (ReflectiveOperationException impossible) { throw new IllegalStateException("Frozen metric record inaccessible", impossible); }
        value.put("boundWork", metrics);
        value.put("posteriorEvidence", "RETAINED_SEPARATE_TYPED_BINARY_LEAVES; NOT_OMITTED_OR_HASH_ONLY");
        value.put("credibleSets", boundedText(result.credibleSets().toString()));
        value.put("gapSummary", boundedText(result.gapSummary().toString()));
        value.put("explanation", boundedText(result.explanation())); return value;
    }

    static final class ExportOverflow extends IllegalArgumentException { ExportOverflow() { super("Complete bounded export unavailable"); } }

    static Map<String, byte[]> posteriorLeaves(String id, ProbabilisticInferenceResult result, CancellationProbe probe) throws Exception {
        requireCase(id); var leaves = new LinkedHashMap<String, byte[]>();
        String[] kinds = {"position", "component", "forward", "backward"};
        for (String kind : kinds) {
            // Exactly one defensive matrix copy is live at a time, inside the funded1MiB report scope.
            List<double[]> matrix = switch (kind) {
                case "position" -> result.positionMarginals(); case "component" -> result.componentMarginals();
                case "forward" -> result.forwardPositionMarginals(); default -> result.backwardPositionMarginals();
            };
            if (matrix.size() > 81) throw new ExportOverflow();
            var buffer = new Buffer(); var out = new DataOutputStream(buffer);
            out.writeInt(0x43535031); out.writeUTF(kind); out.writeInt(matrix.size());
            for (double[] row : matrix) {
                probe.checkpoint(); out.writeInt(row.length);
                for (int i = 0; i < row.length; i++) {
                    if ((i & 1023) == 0) probe.checkpoint(); out.writeLong(Double.doubleToRawLongBits(row[i]));
                }
            }
            leaves.put("posterior-" + id + "-" + kind + ".bin", buffer.toByteArray());
        }
        return leaves;
    }

    /** Owned staging then one bounded admitted commit; cancellation never publishes a route prefix. */
    static void publish(Path output, Map<String, Object> leaves, CancellationProbe probe) throws Exception {
        if (leaves == null || leaves.isEmpty() || leaves.size() > 35) throw new ExportOverflow();
        if (Files.isSymbolicLink(output)) throw new SecurityException("Publication output cannot be a symlink");
        Files.createDirectories(output); Path stage = Files.createTempDirectory(output, ".owned-batch-");
        var names = new ArrayList<String>();
        try {
            for (var entry : leaves.entrySet()) {
                if (!entry.getKey().matches("[a-z0-9-]+\\.json|posterior-[a-z0-9-]+-(position|component|forward|backward)\\.bin")) throw new ExportOverflow();
                byte[] bytes = entry.getValue() instanceof byte[] raw ? raw : encode(entry.getValue());
                writeNew(stage.resolve(entry.getKey()), bytes); names.add(entry.getKey());
            }
            commitStage(stage, output, names, probe);
        } catch (Exception | Error failure) { cleanup(stage, names, List.of(), output, failure); throw failure; }
    }

    private static void commitStage(Path stage, Path output, List<String> names, CancellationProbe probe) throws Exception {
        var published = new ArrayList<String>();
        try {
            if (Files.isSymbolicLink(output)) throw new SecurityException("Publication output changed");
            for (String name : names) if (Files.exists(output.resolve(name), LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(name);
            probe.checkpoint(); // AFTER every complete staged leaf, immediately BEFORE first atomic move.
            for (String name : names) {
                Files.move(stage.resolve(name), output.resolve(name), StandardCopyOption.ATOMIC_MOVE); published.add(name);
            }
            Files.delete(stage);
        } catch (Exception | Error failure) { cleanup(stage, names, published, output, failure); throw failure; }
    }

    private static void cleanup(Path stage, List<String> names, List<String> published, Path output, Throwable primary) {
        // The directory was successfully claimed before assignment; it contains ONLY our flat leaves.
        try (var children = Files.newDirectoryStream(stage)) {
            for (Path child : children) try { Files.delete(child); } catch (Exception failure) { primary.addSuppressed(failure); }
        } catch (NoSuchFileException ignored) { }
        catch (Exception failure) { primary.addSuppressed(failure); }
        for (String name : published) try { Files.deleteIfExists(output.resolve(name)); } catch (Exception failure) { primary.addSuppressed(failure); }
        try { Files.deleteIfExists(stage); } catch (Exception failure) { primary.addSuppressed(failure); }
    }

    private static void writeNew(Path file, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > LEAF_BYTES) throw new ExportOverflow();
        try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) { out.write(bytes); }
    }
    /** Serialize a complete initial phase before claiming its final filename. */
    static void writeInitialPhase(Path phase, Map<String, Object> value) throws IOException {
        writeNew(phase, encode(value));
    }
    private static void updatePhase(Path phase, Run state) throws Exception {
        Path partial = phase.resolveSibling("phase.json.partial"); boolean owned = false;
        try {
            var bytes = encode(state.phase());
            try (var stream = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)) { owned = true; stream.write(bytes); }
            Files.move(partial, phase, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (owned) Files.deleteIfExists(partial); }
    }
    private static byte[] encode(Object value) {
        // Refuse BEFORE the shared canonical StringBuilder can grow beyond a leaf.
        // This conservative UTF8 byte count is exact for the fixed ASCII metadata;
        // surrogate code units may be overestimated, never underestimated.
        jsonSize(value, 0, new long[2]);
        byte[] bytes;
        try { bytes = BIdentificationReceipt.canonical(value).getBytes(StandardCharsets.UTF_8); }
        catch (IllegalArgumentException tooLargeOrInvalid) { throw new ExportOverflow(); }
        if (bytes.length == 0 || bytes.length > LEAF_BYTES) throw new ExportOverflow(); return bytes;
    }
    private static void jsonSize(Object value, int depth, long[] count) {
        if (depth > 16 || ++count[1] > 32_768) throw new ExportOverflow();
        if (value == null) sizeAdd(count, 4);
        else if (value instanceof String text) {
            sizeAdd(count, 2);
            for (int i=0;i<text.length();i++) {
                char c=text.charAt(i); sizeAdd(count,c=='"'||c=='\\'?2:c<32?6:c<128?1:c<2048?2:3);
            }
        } else if (value instanceof Map<?,?> map) {
            sizeAdd(count, 2); boolean first=true;
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String))throw new ExportOverflow();
                if(!first)sizeAdd(count,1);first=false;jsonSize(entry.getKey(),depth+1,count);sizeAdd(count,1);jsonSize(entry.getValue(),depth+1,count);
            }
        } else if (value instanceof java.util.Collection<?> values) {
            sizeAdd(count,2);boolean first=true;for(Object item:values){if(!first)sizeAdd(count,1);first=false;jsonSize(item,depth+1,count);}
        } else if(value instanceof Boolean bool)sizeAdd(count,bool?4:5);
        else if(value instanceof Number number) {
            if(!Double.isFinite(number.doubleValue()))throw new ExportOverflow();sizeAdd(count,number.toString().length());
        } else throw new ExportOverflow();
    }
    private static void sizeAdd(long[] count,long amount){count[0]=Math.addExact(count[0],amount);if(count[0]>LEAF_BYTES)throw new ExportOverflow();}
    private static byte[] boundedRead(Path path) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(LEAF_BYTES + 1); if (bytes.length == 0 || bytes.length > LEAF_BYTES) throw new ExportOverflow(); return bytes;
        }
    }
    private static String boundedText(String text) { if (text == null || text.length() > LEAF_BYTES) throw new ExportOverflow(); return text; }
    private static List<List<String>> pointBits(List<MetricPoint> points) { return points.stream().map(p -> List.of(bits(p.xMeters()), bits(p.yMeters()))).toList(); }
    private static List<List<Double>> points(List<MetricPoint> points) { return points.stream().map(p -> List.of(p.xMeters(), p.yMeters())).toList(); }
    private static Map<String, Object> frameBits(EvidenceSnapshot evidence) {
        var f = evidence.coordinateFrame(); var d = f.distortionCertificate(); var t = evidence.transform();
        var result = new LinkedHashMap<String, Object>();
        result.put("projectionId", f.projectionId()); result.put("originLatitudeLongitude", List.of(bits(f.origin().latitudeDegrees()), bits(f.origin().longitudeDegrees())));
        result.put("certificateMethod", d.method()); result.put("certificateSouthWest", List.of(bits(d.southWest().latitudeDegrees()), bits(d.southWest().longitudeDegrees())));
        result.put("certificateNorthEast", List.of(bits(d.northEast().latitudeDegrees()), bits(d.northEast().longitudeDegrees())));
        result.put("distanceErrorEastNorthScales", List.of(bits(d.maximumRelativeDistanceError()), bits(d.eastMetersPerRadian()), bits(d.northMetersPerRadian())));
        result.put("transformId", t.transformId()); result.put("transformOriginXY", List.of(bits(t.origin().xMeters()), bits(t.origin().yMeters())));
        result.put("axisKind", List.of(t.originKind().name(), t.axisUnit().name()));
        result.put("affineAxesAndOversampling", List.of(bits(t.xAxisEastMetersPerSourcePixel()), bits(t.xAxisNorthMetersPerSourcePixel()),
                bits(t.yAxisEastMetersPerSourcePixel()), bits(t.yAxisNorthMetersPerSourcePixel()), bits(t.rasterPixelsPerSourcePixel())));
        result.put("transformCertificate", Map.of("method", t.accuracyCertificate().method(), "errorBits", bits(t.accuracyCertificate().maximumErrorMeters()),
                "toleranceBits", bits(t.accuracyCertificate().toleranceMeters()), "verificationPoints", t.accuracyCertificate().verificationPointCount()));
        return result;
    }
    private static void sha(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new SecurityException("External SHA256 required"); }
    private static void commit(String value) { if (value == null || !value.matches("[0-9a-f]{40}")) throw new SecurityException("Actual commit identity required"); }
    private static String text(Object value) { if (!(value instanceof String text)) throw new SecurityException("String protocol value required"); return text; }
    private static long integer(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new SecurityException("Exact integer protocol value required");
        return ((Number) value).longValue();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> result) || result.keySet().stream().anyMatch(k -> !(k instanceof String))) throw new SecurityException("Protocol object required");
        return (Map<String, Object>) result;
    }
    private static List<?> list(Object value) { if (!(value instanceof List<?> result) || result.size() > 128) throw new SecurityException("Bounded protocol list required"); return result; }
    static List<Map<String, Object>> routes(List<ProbabilisticPath> paths) {
        if (paths == null || paths.size() > 32) throw new IllegalArgumentException("Returned route inventory limit");
        var result = new ArrayList<Map<String, Object>>();
        for (var path : paths) {
            if (path == null || path.points().size() > 81) throw new IllegalArgumentException("Complete route point limit");
            var row = new LinkedHashMap<String, Object>();
            row.put("states", Arrays.stream(path.stateIndices()).boxed().toList());
            row.put("points", path.points().stream().map(p -> List.of(p.xMeters(), p.yMeters())).toList());
            row.put("pointBits", path.points().stream().map(p -> List.of(bits(p.xMeters()), bits(p.yMeters()))).toList());
            row.put("branchSignature", path.branchSignature());
            row.put("energy", path.energy()); row.put("energyBits", bits(path.energy()));
            row.put("logBaseMeasure", path.logBaseMeasure()); row.put("logBaseMeasureBits", bits(path.logBaseMeasure()));
            row.put("posterior", path.conditionalPosteriorMass()); row.put("posteriorBits", bits(path.conditionalPosteriorMass()));
            encode(row); // Complete leaf or an explicit export refusal; never truncate.
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static String bits(double value) { return Long.toHexString(Double.doubleToRawLongBits(value)); }
}
