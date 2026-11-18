package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorGapDomain;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;

/** Detached public finite-width faint strands; invokes the actual frozen R4 adapter. */
class CorridorGapProductionTest {
    @Test void unavailableCertificateKeepsR4AdmissionPolicyButAddsNoGapStates() {
        var f = CorridorPolicyFixture.straight(true, false, Set.of()); var r = f.request();
        var way = (DetachedWay)f.network().primitives().get(r.selectedWayKey());
        var geographic = way.nodeKeys().stream().map(k -> ((DetachedNode)f.network().primitives().get(k)).coordinate()).toList();
        var metric = geographic.stream().map(f.evidence().coordinateFrame()::toMetric).toList();
        double total = 0;
        for (int i = 1; i < metric.size(); i++) total += metric.get(i - 1).distanceTo(metric.get(i));
        double end = Math.nextDown(total); var distances = new ArrayList<Double>();
        for (int i = 0; i < 30; i++) distances.add(i == 29 ? end : end * i / 29);
        var chainage = new ProfileChainage(distances, total / 29);
        var budgets = new TraceBudgets(96, 15000, 128000000, 32, 8);
        for (var policy : List.of(CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1,
                CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1)) {
            var input = CorridorTraceInput.from(chainage, metric, f.evidence().coordinateFrame(), f.evidence().transform(), .5).withPolicy(policy, Set.of());
            var request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(), r.permissions(), budgets,
                    r.evidenceSnapshotId(), r.evidenceContentHash(), r.networkSnapshotId(), r.networkContentHash(), r.settingsHash(), r.parameterHash(),
                    r.samplerId(), total / 29, chainage, r.evidenceResolution(), Optional.of(input));
            var run = new CorridorEngineAdapter("native").traceWithUsage(request, f.evidence(), f.network(), CancellationProbe.NONE);
            var receipt = run.corridorReceipt().orElseThrow();
            assertEquals("REFUSED_SOURCE_PROTECTION_CHAINAGE_OWNERSHIP", receipt.certificateStatus());
            if (policy == CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1) {
                assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, run.result().status()); assertEquals(0, receipt.visitedTracks());
            } else {
                assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, run.result().status(), run.result()::explanation);
                assertTrue(receipt.visitedTracks() > 0); assertTrue(receipt.totals().solverStarts() > 0);
                assertTrue(receipt.tracks().values().stream().allMatch(t -> t.preparation().gapWork().isEmpty()));
            }
        }
    }
    @Test void closeParallelFineChildrenMayUseCoarsePersistenceWithoutTakingAMidpoint() {
        var f = strands(6);
        var run = new CorridorEngineAdapter("native").traceWithUsage(f.request(), f.evidence(), f.network(), CancellationProbe.NONE);
        var receipt = run.corridorReceipt().orElseThrow();
        assertFalse(receipt.tracks().isEmpty());
        var work = receipt.tracks().values().stream().map(t -> t.preparation().gapWork().orElseThrow()).toList();
        assertTrue(work.stream().flatMap(w -> w.rows().stream()).anyMatch(r -> r.status().equals("CERTIFIED_COARSE_PERSISTENCE")), work::toString);
        assertTrue(run.result().hypotheses().stream().anyMatch(h -> h.points().stream().mapToDouble(MetricPoint::yMeters).average().orElseThrow() < 47));
        assertTrue(run.result().hypotheses().stream().anyMatch(h -> h.points().stream().mapToDouble(MetricPoint::yMeters).average().orElseThrow() > 54));
    }
    @Test void actualR4MultiStrandGapsRetainRawOwnershipAndChargeSourceWorkOnce() {
        var fixture = strands();
        var run = new CorridorEngineAdapter("native").traceWithUsage(fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);
        assertFalse(run.result().hypotheses().isEmpty(), run.result()::explanation);
        var receipt = run.corridorReceipt().orElseThrow();
        var work = receipt.tracks().values().stream().map(t -> t.preparation().gapWork().orElseThrow()).toList();
        long added = work.stream().mapToLong(CorridorGapDomain.Work::addedStates).sum();
        assertTrue(added > 0, () -> "Actual extraction/association must expose eligible gaps: " + work);
        assertTrue(run.result().hypotheses().stream().anyMatch(h -> h.support().contains(ObservationOwnership.INFERRED_GAP)),
                "Gap choices must retain unobserved raw provenance");
        long source = work.stream().mapToLong(CorridorGapDomain.Work::admissionUnits).sum();
        long other = receipt.tracks().values().stream().mapToLong(t -> t.contactTests() + t.pairTests() + t.adjacentObjectiveEntries()).sum();
        assertEquals(other + source, run.usage().pairVisits(), "Source admission is charged exactly once, separately from DP adjacency");
        assertTrue(work.stream().flatMap(w -> w.rows().stream()).allMatch(r -> r.addedOffsets().size() <= 2));
        var pipeline = new ModernTracePipeline(new CorridorEngineAdapter("native")).runWithUsage(fixture.request(),
                fixture.evidence(), fixture.network(), fixture.options(), CancellationProbe.NONE);
        assertFalse(pipeline.result().routes().isEmpty());
        assertTrue(pipeline.result().routes().stream().anyMatch(r -> r.rawHypothesis().support().contains(ObservationOwnership.INFERRED_GAP)),
                "Fresh final support extraction must not overwrite raw provenance");
        var input = new FrozenReplayInput(fixture.request(), fixture.evidence(), fixture.network(), fixture.options());
        var bundle = Format15ProductionBundleFactory.createLive("public-r4-gap", input, pipeline.result(),
                "preview", "synthetic", 0, null, false, false);
        bundle = CorridorExecutionArtifacts.attach(bundle, input, pipeline.result().inference(), pipeline.corridorReceipt().orElseThrow());
        String csv = new String(bundle.artifact("corridor-execution.csv").bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("R4_OWNED_GAP_DOMAIN_V1")); assertTrue(csv.contains("gap_source_admission_units"));
        assertTrue(csv.contains("historical_offset_px")); assertTrue(csv.contains("INFERRED_GAP"));
    }

    @Test void wideMissingStrandWithActualScaleConflictKeepsHistoricalDomain() {
        var f = strands(16, 5);
        var run = new CorridorEngineAdapter("native").traceWithUsage(f.request(), f.evidence(), f.network(), CancellationProbe.NONE);
        var work = run.corridorReceipt().orElseThrow().tracks().values().stream().map(t -> t.preparation().gapWork().orElseThrow()).toList();
        assertTrue(work.stream().flatMap(w -> w.rows().stream()).anyMatch(r -> r.status().equals("TUBE_SCALE_CONFLICT")), work::toString);
        assertEquals(0, work.stream().mapToLong(CorridorGapDomain.Work::addedStates).sum());
    }

    static CorridorPolicyFixture.Fixture strands() {
        return strands(6, 5);
    }
    static CorridorPolicyFixture.Fixture strands(double separation) {
        return strands(separation, 5);
    }
    static CorridorPolicyFixture.Fixture strands(double separation, int gapWidth) {
        var base = CorridorPolicyFixture.straight(true, true, Set.of()); var old = base.evidence();
        int width = 160, height = 100; var values = new double[width * height]; var valid = new boolean[values.length]; Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double value = 0;
            for (int strand = -1; strand <= 1; strand++) {
                if (strand == 0 && (x >= 70 && x < 70 + gapWidth || x >= 95 && x < 95 + gapWidth)) continue;
                double center = 50.3 + strand * separation + .6 * Math.sin(x / 13.0 + strand);
                value += .22 * Math.exp(-.5 * Math.pow((y - center) / .65, 2));
            }
            values[y * width + x] = value;
        }
        var lineage = old.fields().get("native").lineage();
        var evidence = new EvidenceSnapshot("public-r4-strands", old.coordinateFrame(), old.transform(), old.resolution(),
                old.decisionRegion(), old.evidenceRegion(), Map.of("native", new ScalarEvidenceField(width, height, values, valid, lineage)), "public-r4-strands");
        var r = base.request(); var selected = (DetachedWay)base.network().primitives().get(r.selectedWayKey());
        var geographic = selected.nodeKeys().stream().map(k -> ((DetachedNode)base.network().primitives().get(k)).coordinate()).toList();
        var metric = geographic.stream().map(evidence.coordinateFrame()::toMetric).toList();
        var chainage = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory().profileChainage(metric, 1.5);
        var input = CorridorTraceInput.from(chainage, metric, evidence.coordinateFrame(), evidence.transform(), .5)
                .withPolicy(CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1, Set.of());
        var request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(), r.permissions(), r.budgets(),
                evidence.snapshotId(), evidence.canonicalHash(), r.networkSnapshotId(), r.networkContentHash(), r.settingsHash(),
                "4".repeat(64), "synthetic-r4-gap", 1.5, chainage, evidence.resolution(), Optional.of(input));
        return new CorridorPolicyFixture.Fixture(request, evidence, base.network(), base.options());
    }
}
