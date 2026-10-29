package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Section;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.SectionChoice;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner;

/**
 * Bounded canonical record of actual intermediate Hybrid outputs, not reconstructed archive claims.
 * Schema 1 uses DataOutput primitives and length-prefixed UTF-8 strings, starting with LBB1.
 * Ordered blocks retain native Legacy alternatives, retained seeds, section references/execution,
 * local quality exclusions, assembled choices and measured section support. Metric and projected
 * coordinates stay separate. Native B work remains in its dedicated work artifact.
 */
final class LegacyBProvenance {
    static final String ARTIFACT = "legacy-b-provenance.bin";
    private LegacyBProvenance() { }

    static Format15Artifact artifact(LegacyBProductionRunner.Input input, LegacyBProductionRunner.Result result) {
        var bytes = new BoundedOutput();
        try (var data = new DataOutputStream(bytes)) {
            data.writeInt(0x4c424231);
            data.writeInt(1);
            text(data, "legacy-then-b-v1");
            text(data, input.request().networkContentHash());
            text(data, input.request().evidenceContentHash());
            text(data, input.request().parameterHash());
            text(data, result.detector());
            data.writeInt(result.profileCount());
            data.writeInt(result.alternatives().size());
            for (var alternative : result.alternatives()) {
                text(data, alternative.id()); text(data, alternative.detector()); text(data, alternative.nativeId());
                data.writeInt(alternative.nativeRank()); number(data, alternative.score());
                data.writeInt(alternative.rasterPoints().size());
                for (var point : alternative.rasterPoints()) { number(data, point.x()); number(data, point.y()); }
                data.writeInt(alternative.offsetsPx().size());
                for (double offset : alternative.offsetsPx()) number(data, offset);
                projected(data, alternative.rawProjected()); projected(data, alternative.finalProjected());
                text(data, alternative.seed().map(seed -> seed.id()).orElse(""));
                text(data, alternative.rejection().map(Enum::name).orElse(""));
                data.writeInt(alternative.safetyWarnings().size());
                for (String warning : alternative.safetyWarnings()) text(data, warning);
            }
            data.writeInt(result.seeds().size());
            for (var seed : result.seeds()) {
                text(data, seed.id()); points(data, seed.points()); mapping(data, seed.originalToVertex());
            }
            var batch = result.batch();
            text(data, batch.status().name()); data.writeBoolean(batch.truncated());
            data.writeInt(batch.omittedParents()); data.writeLong(batch.usage().pairVisits());
            data.writeLong(batch.usage().transitions());
            data.writeInt(batch.sections().size());
            for (var outcome : batch.sections()) {
                section(data, outcome.section()); text(data, outcome.outcome().name()); text(data, outcome.reason().name());
                data.writeBoolean(outcome.execution().isPresent());
                if (outcome.execution().isPresent()) {
                    var execution = outcome.execution().orElseThrow();
                    var reference = execution.reference().orElseThrow(() ->
                            new IllegalArgumentException("Actual Hybrid section lacks reference provenance"));
                    var request = reference.request();
                    text(data, request.evidenceContentHash()); text(data, request.networkContentHash());
                    text(data, request.parameterHash()); text(data, request.samplerId());
                    var budget = request.budgets();
                    data.writeInt(budget.maximumStatesPerProfile()); data.writeLong(budget.maximumPairVisits());
                    data.writeLong(budget.maximumTransitions()); data.writeInt(budget.maximumRawAlternatives());
                    data.writeInt(budget.maximumDistinctAlternatives());
                    var nativeOutput = execution.run().result();
                    text(data, nativeOutput.status().name()); data.writeBoolean(nativeOutput.truncated());
                    data.writeLong(nativeOutput.evaluatedStates()); data.writeLong(nativeOutput.evaluatedTransitions());
                    data.writeInt(nativeOutput.hypotheses().size());
                    for (var hypothesis : nativeOutput.hypotheses()) ReplayOutputCanonical.writeHypothesis(data, hypothesis);
                    var eligible = execution.eligibleHypothesisIds().stream().sorted().toList();
                    data.writeInt(eligible.size()); for (String id : eligible) text(data, id);
                }
            }
            data.writeInt(result.sectionQualities().size());
            for (var quality : result.sectionQualities()) {
                text(data, quality.sectionId()); text(data, quality.hypothesisId());
                data.writeBoolean(quality.eligible()); ReplayOutputCanonical.writeQuality(data, quality.quality());
            }
            data.writeInt(batch.candidates().size());
            for (var candidate : batch.candidates()) {
                text(data, candidate.id()); text(data, candidate.parentSeedId());
                points(data, candidate.points()); mapping(data, candidate.originalToVertex());
                data.writeInt(candidate.sections().size());
                for (var choice : candidate.sections()) choice(data, choice);
            }
            ReplayOutputCanonical.writeQuality(data, result.originalQuality());
            data.writeInt(result.assessments().size());
            for (var assessed : result.assessments()) {
                text(data, assessed.candidate().id()); ReplayOutputCanonical.writeQuality(data, assessed.quality());
                optional(data, assessed.directlySupportedLengthMeters());
                data.writeInt(assessed.pointIds().size());
                for (var id : assessed.pointIds()) {
                    ReplayOutputCanonical.writePointId(data, id);
                    ReplayOutputCanonical.writePoint(data, assessed.assignments().get(id));
                }
                data.writeInt(assessed.sections().size());
                for (var support : assessed.sections()) {
                    choice(data, support.choice()); number(data, support.totalLengthMeters());
                    optional(data, support.directlySupportedLengthMeters()); optional(data, support.nativeBPosterior());
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode bounded Hybrid provenance", failure);
        }
        return Format15Artifact.binary(ARTIFACT, bytes.content.toByteArray());
    }

    private static void choice(DataOutputStream data, SectionChoice choice) throws IOException {
        text(data, choice.section().id()); data.writeInt(choice.vertices().first()); data.writeInt(choice.vertices().last());
        text(data, choice.source().name()); text(data, choice.outcome().name()); text(data, choice.reason().name());
        text(data, choice.hypothesisId().orElse(""));
    }

    private static void section(DataOutputStream data, Section section) throws IOException {
        text(data, section.id()); text(data, section.parentSeedId()); data.writeBoolean(section.frozen());
        data.writeInt(section.originalRange().firstIndex()); data.writeInt(section.originalRange().lastIndex());
        points(data, section.reference()); mapping(data, section.originalToVertex());
    }

    private static void points(DataOutputStream data, List<MetricPoint> points) throws IOException {
        data.writeInt(points.size()); for (var point : points) ReplayOutputCanonical.writePoint(data, point);
    }

    private static void projected(DataOutputStream data,
            List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint> points)
            throws IOException {
        data.writeInt(points.size()); for (var point : points) { number(data, point.east()); number(data, point.north()); }
    }

    private static void mapping(DataOutputStream data, Map<Integer,Integer> mapping) throws IOException {
        data.writeInt(mapping.size());
        for (var row : new TreeMap<>(mapping).entrySet()) { data.writeInt(row.getKey()); data.writeInt(row.getValue()); }
    }

    private static void optional(DataOutputStream data, OptionalDouble value) throws IOException {
        data.writeBoolean(value.isPresent()); if (value.isPresent()) number(data, value.orElseThrow());
    }
    private static void number(DataOutputStream data, double value) throws IOException {
        ReplayOutputCanonical.writeFiniteDouble(data, value);
    }
    private static void text(DataOutputStream data, String value) throws IOException {
        Format15Safety.requireSafeExportedMetadata(value); ScalarReplayFingerprint.writeString(data, value);
    }

    /** Enforces the existing artifact ceiling before growing the backing buffer. */
    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        @Override public void write(int value) {
            reserve(1); content.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) {
            reserve(length); content.write(value, offset, length);
        }
        private void reserve(int length) {
            if (length < 0 || length > Format15Safety.MAX_ARTIFACT_BYTES - content.size())
                throw new IllegalArgumentException("legacy-provenance-byte-budget");
        }
    }
}
