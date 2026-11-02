package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class OptionalEmissionContextTest {
    private static final String FIELD = "native";

    @Test
    void retainedInputKeepsActualNativeFieldAndUnchangedProfileEvidence() {
        Fixture f = fixture(EvidenceResolution.nativeSource(2, 1));
        var ledger = AttemptMemoryLedger.production();
        var work = budget();
        try (var owner = ledger.rootOwner(); var sampled = sample(f, work, owner)) {
            assertNotNull(sampled, "The explicit retained producer must return the native L0 input");
            var context = sampled.context();
            assertEquals((double)(float)0.123456789, context.nativeGrid().nodeValue(4, 4), 0);
            assertFalse(context.nativeGrid().supportsCell(4, 4),
                "Valid corners must not replace the complete-cell mask");
            assertEquals(2, context.nativePitchMeters().orElseThrow());
            assertEquals(1, context.outputRasterPitchMeters());
            assertEquals(1, context.profileCoordinateScale());
            assertEquals(3, context.searchHalfWidthMeters());
            assertEquals(1, context.lateralStepMeters());
            assertSame(work, sampled.workBudget(), "Producer and later execution share one existing budget");
            assertSame(f.evidence().decisionRegion(), context.decisionRegion());
            assertSame(f.evidence().evidenceRegion(), context.evidenceRegion());
            assertNotEquals(context.decisionRegion(), context.evidenceRegion());
            var nativeProfiles = new DetachedScalarProfileSampler().sample(f.evidence(), FIELD,
                f.input().profileLocations(), 3, 1, CancellationProbe.NONE);
            assertProfilesEqual(nativeProfiles, sampled.profiles());
            assertTrue(ledger.currentBytes() > 0);
        }
        assertEquals(0, ledger.currentBytes(), "Closing the input must release its retained lifetime");
    }

    @Test
    void factorySharesTheExactL0ObjectThatProducedTheProfiles() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        var work = budget();
        try (var owner = AttemptMemoryLedger.production().rootOwner();
             var binding = OptionalEmissionContextFactory.bindDetached(f.request(), f.evidence(), FIELD, work, owner)) {
            assertNotNull(binding, "The source binding must be established before production");
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(f.evidence(), FIELD,
                f.input().profileLocations(), 3, 1, 1, work);
            assertNotNull(retained);
            try (var result = OptionalEmissionContextFactory.fromDetached(binding, retained)) {
                assertNotNull(result);
                assertSame(retained.levelZero(), result.context().nativeField());
                assertSame(retained.profiles(), result.profiles());
            }
        }
    }

    @Test
    void retainedL0SourceReadsChargeTheExistingAttemptBeforeMaterialization() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        var work = budget();
        try (var owner = AttemptMemoryLedger.production().rootOwner(); var result = sample(f, work, owner)) {
            assertNotNull(result);
            assertEquals(1600L, work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.NODE_READ));
            assertEquals(1521L, work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.CELL_QUERY));
        }
    }

    @Test
    void cancellationDuringSourceReadsCannotPrechargeUnreadPixelsOrPublishPartialInput() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        var ref = new AtomicReference<EmissionWorkBudget>();
        var work = new EmissionWorkBudget(TraceBudgets.defaults(), () -> ref.get() != null
            && ref.get().snapshot().operationUnits().get(EmissionWorkBudget.Operation.NODE_READ) >= 10);
        ref.set(work);
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            assertThrows(CancellationException.class, () -> sample(f, work, owner));
            assertEquals(10L, work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.NODE_READ));
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void originalGroundRowsAreImmutableAndAChangedNormalInvalidatesTheBinding() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        try (var owner = AttemptMemoryLedger.production().rootOwner(); var result = sample(f, budget(), owner)) {
            assertNotNull(result);
            assertEquals(List.of(0.0, 10.0), result.context().observations().stream()
                .map(row -> row.location().cumulativeGroundDistanceMeters()).toList());
            assertEquals(new RasterPoint(-0.0, 1), result.context().observations().get(0).normal());
            assertThrows(UnsupportedOperationException.class, () -> result.context().observations().clear());
            result.profiles().levelZeroProfiles().get(0).normalScreen().x = 0.25;
            assertThrows(IllegalStateException.class, result::requireOpenAndUnchanged);
            assertEquals(new RasterPoint(-0.0, 1), result.context().observations().get(0).normal());
        }
    }

    @Test
    void mutatingAllProducerNormalsBeforePublicationCannotCertifyInventedFacts() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        var work = budget();
        try (var owner = AttemptMemoryLedger.production().rootOwner();
             var binding = OptionalEmissionContextFactory.bindDetached(f.request(), f.evidence(), FIELD, work, owner)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(f.evidence(), FIELD,
                f.input().profileLocations(), 3, 1, 1, work);
            for (var level : retained.profiles().levels()) {
                level.profiles().get(0).normalScreen().x = 0.25;
            }
            assertThrows(IllegalStateException.class,
                () -> OptionalEmissionContextFactory.fromDetached(binding, retained),
                "The captured normal must derive from immutable source locations, not mutable producer objects");
        }
    }

    @Test
    void snapshotSourceAndContentMismatchCannotProduceAContext() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        for (TraceRequest bad : List.of(copy(f.request(), "another-source", f.request().evidenceContentHash(),
                f.input(), f.request().profileChainage()),
                copy(f.request(), f.request().evidenceSnapshotId(), "incorrect-hash", f.input(),
                    f.request().profileChainage()))) {
            var ledger = AttemptMemoryLedger.production();
            try (var owner = ledger.rootOwner()) {
                assertThrows(IllegalArgumentException.class,
                    () -> new DetachedScalarProfileSampler().sampleForEmission(bad, f.evidence(), FIELD,
                        budget(), owner));
                assertEquals(0, ledger.currentBytes());
            }
        }
    }

    @Test
    void countChainageAndFramePhaseMismatchesAreRejected() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        var wrongDistances = new ProfileChainage(List.of(0.0, 9.0), 10);
        var extra = new CorridorTraceInput(List.of(f.input().profileLocations().get(0),
            at(f.evidence(), new RasterPoint(15, 20), 5), f.input().profileLocations().get(1)), 1);
        var moved = RasterMetricTransform.metricGrid(new MetricPoint(0.5, 0), 1, 0, 0, 1);
        var phaseLocations = new ArrayList<DetachedProfileSamplingLocation>();
        for (var old : f.input().profileLocations()) {
            var metric = moved.pixelCenterToMetric(old.rasterPoint().x(), old.rasterPoint().y());
            phaseLocations.add(DetachedProfileSamplingLocation.at(f.evidence().coordinateFrame()
                .toGeographic(metric), f.evidence().coordinateFrame(), moved,
                old.cumulativeGroundDistanceMeters()));
        }
        for (var request : List.of(copy(f.request(), f.request().evidenceSnapshotId(),
                f.request().evidenceContentHash(), f.input(), wrongDistances),
                copy(f.request(), f.request().evidenceSnapshotId(), f.request().evidenceContentHash(),
                    extra, f.request().profileChainage()),
                copy(f.request(), f.request().evidenceSnapshotId(), f.request().evidenceContentHash(),
                    new CorridorTraceInput(phaseLocations, 1), f.request().profileChainage()))) {
            try (var owner = AttemptMemoryLedger.production().rootOwner()) {
                assertThrows(IllegalArgumentException.class, () -> new DetachedScalarProfileSampler()
                    .sampleForEmission(request, f.evidence(), FIELD, budget(), owner));
            }
        }
    }

    @Test
    void declaredSyntheticIsExplicitlyNonproductionAndNativePitchAbsenceIsTruthful() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        try (var owner = AttemptMemoryLedger.production().rootOwner(); var result = new DetachedScalarProfileSampler()
                .sampleDeclaredSyntheticForEmission(f.evidence(), FIELD, f.input(),
                    f.request().profileChainage(), 3, budget(), owner)) {
            assertNotNull(result);
            assertEquals(OptionalEmissionContext.Origin.DECLARED_SYNTHETIC_METRIC_GRID, result.context().origin());
            assertEquals(OptionalEmissionContext.Eligibility.DECLARED_SYNTHETIC, result.context().eligibility());
            assertFalse(result.context().productionEligible());
        }
        Fixture unknown = fixture(EvidenceResolution.renderedOnly(1));
        try (var owner = AttemptMemoryLedger.production().rootOwner(); var result = sample(unknown, budget(), owner)) {
            assertNotNull(result);
            assertTrue(result.context().nativePitchMeters().isEmpty());
            assertFalse(result.context().productionEligible());
            assertEquals(1, result.context().outputRasterPitchMeters());
        }
    }

    @Test
    void compatibilityProfilesCannotBePromotedByNumericallyMatchingChainage() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        try (var owner = AttemptMemoryLedger.production().rootOwner(); var result = sample(f, budget(), owner)) {
            assertNotNull(result);
            var compatible = new MultiScaleProfileSet(List.of(new MultiScaleProfileSet.ScaleProfileLevel(0, 1, 0,
                result.profiles().levelZeroProfiles().stream().map(p -> new RenderedHeatmapSampler.CrossSectionProfile(
                    new ProfileSamplingAnchor(new org.openstreetmap.josm.data.coor.EastNorth(
                        p.anchorScreen().x, p.anchorScreen().y), p.anchorScreen().x, p.anchorScreen().y,
                        p.cumulativeGroundDistanceMeters()), p.normalScreen(), p.peaks(), p.anchorWithinRaster(),
                    p.intensitySamples())).toList())), 0, 0);
            assertThrows(IllegalStateException.class, () -> result.context().requireProfilesUnchanged(compatible));
        }
    }

    @Test
    void callerMemoryAndSharedWorkLimitsRefuseBeforeMaterialization() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        var ledger = new AttemptMemoryLedger(1);
        try (var owner = ledger.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> sample(f, budget(), owner));
            assertEquals(0, ledger.currentBytes());
        }
        var small = new EmissionWorkBudget(new TraceBudgets(96, 8, 1, 32, 8), CancellationProbe.NONE);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            assertThrows(EmissionWorkBudget.LimitExceededException.class, () -> sample(f, small, owner));
        }
    }

    @Test
    void cancellationBeforeAndWithinTheActualProducerReleasesRetainedMemory() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        AtomicInteger producerCheckpoints = new AtomicInteger();
        for (CancellationProbe probe : List.<CancellationProbe>of(() -> true,
            () -> StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getMethodName().equals("fromEvidence"))) && producerCheckpoints.incrementAndGet() >= 2)) {
            var ledger = AttemptMemoryLedger.production();
            var work = new EmissionWorkBudget(TraceBudgets.defaults(), probe);
            try (var owner = ledger.rootOwner()) {
                assertThrows(CancellationException.class, () -> sample(f, work, owner));
                assertEquals(0, ledger.currentBytes());
            }
        }
    }

    @Test
    void closedInputCannotBeUsedAsAnAdmittedExecutionBinding() {
        Fixture f = fixture(EvidenceResolution.nativeSource(1, 1));
        var work = budget();
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var result = sample(f, work, owner);
            assertNotNull(result);
            assertSame(work, result.workBudget());
            result.close();
            result.close();
            assertThrows(IllegalStateException.class, result::requireOpenAndUnchanged);
        }
    }

    private static SampledEmissionInput sample(Fixture f, EmissionWorkBudget work, AttemptMemoryLedger.Owner owner) {
        return new DetachedScalarProfileSampler().sampleForEmission(f.request(), f.evidence(), FIELD, work, owner);
    }
    private static EmissionWorkBudget budget() { return new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE); }
    private static void assertProfilesEqual(MultiScaleProfileSet a, MultiScaleProfileSet b) {
        assertEquals(a.levels().size(), b.levels().size());
        for (int l = 0; l < a.levels().size(); l++) {
            assertEquals(a.levels().get(l).level(), b.levels().get(l).level());
            assertEquals(a.levels().get(l).reduction(), b.levels().get(l).reduction());
            var x = a.levels().get(l).profiles(); var y = b.levels().get(l).profiles();
            assertEquals(x.size(), y.size());
            for (int i = 0; i < x.size(); i++) {
                assertSame(x.get(i).samplingAnchor(), y.get(i).samplingAnchor());
                assertEquals(x.get(i).normalScreen(), y.get(i).normalScreen());
                assertEquals(x.get(i).peaks(), y.get(i).peaks());
                assertEquals(x.get(i).intensitySamples(), y.get(i).intensitySamples());
            }
        }
    }
    private record Fixture(EvidenceSnapshot evidence, CorridorTraceInput input, TraceRequest request) { }
    private static Fixture fixture(EvidenceResolution resolution) {
        int w = 40, h = 40; double[] values = new double[w*h]; Arrays.fill(values, 0.123456789);
        boolean[] valid = new boolean[w*h]; Arrays.fill(valid, true);
        boolean[] cells = new boolean[(w-1)*(h-1)]; Arrays.fill(cells, true); cells[4*(w-1)+4] = false;
        var origin = new GeographicPoint(50, 14);
        var frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var field = new ScalarEvidenceField(w,h,values,valid,cells,new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC, EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
            FIELD,EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var evidence = new EvidenceSnapshot("context-source",frame,transform,resolution,
            MetricRegion.rectangle(5, 5, 35, 35), MetricRegion.rectangle(-0.5,-0.5,39.5,39.5),
            Map.of(FIELD,field),"declared-test-source");
        var input = new CorridorTraceInput(List.of(at(evidence,new RasterPoint(10,20),0),
            at(evidence,new RasterPoint(20,20),10)),1);
        var chainage = new ProfileChainage(List.of(0.0,10.0),10);
        var request = new TraceRequest(PrimitiveKey.existing(PrimitiveKey.Type.WAY,1),new OccurrenceRange(0,1),
            TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE,RecoveryPermissions.disabled(3),
            TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),"network","network-hash",
            "settings","parameters","detached-scalar",10,chainage,resolution,Optional.of(input));
        return new Fixture(evidence,input,request);
    }
    private static DetachedProfileSamplingLocation at(EvidenceSnapshot evidence,RasterPoint p,double s) {
        var metric=evidence.transform().pixelCenterToMetric(p.x(),p.y());
        return new DetachedProfileSamplingLocation(evidence.coordinateFrame().toGeographic(metric),metric,p,
            evidence.coordinateFrame(),evidence.transform(),s);
    }
    private static TraceRequest copy(TraceRequest r,String snapshot,String hash,CorridorTraceInput input,
            ProfileChainage distances) {
        return new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),
            r.budgets(),snapshot,hash,r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),
            r.samplerId(),r.configuredSampleStepMeters(),distances,r.evidenceResolution(),Optional.of(input));
    }
}
