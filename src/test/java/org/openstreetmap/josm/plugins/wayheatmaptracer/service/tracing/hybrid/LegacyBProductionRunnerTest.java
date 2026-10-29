package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

class LegacyBProductionRunnerTest {
    @Test void inheritedCliffIsReviewableBeforeSectionAdmissionAndOnAssembledGeometry() {
        var fixture = taggedContextFixture(Map.of("natural","cliff"),true);
        var hash = fixture.input().network().canonicalHash();
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var run = new LegacyBProductionRunner().runVisible(fixture.input(),fixture.visible(),
                    CancellationProbe.NONE,owner,LegacyBPipeline.Deadline.production());
            assertTrue(run.value().assessments().stream().anyMatch(row ->
                    !row.quality().has(FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING)
                    && row.quality().has(FinalGeometryEvaluator.FindingCode.INDEPENDENT_CONTEXT_CONTACT)),
                    "A fully proved inherited cliff must be reviewed rather than blocked as a new crossing");
            assertTrue(run.value().batch().sections().stream().anyMatch(section ->
                    section.outcome() == LegacyBPipeline.Outcome.B_REFINED),
                    "The narrow contact proof must run before local B eligibility is decided");
            assertEquals(hash,fixture.input().network().canonicalHash());
        }
    }

    @Test void unprovedContextCrossingsRemainHardDuringHybridRefinement() {
        for (var tags : List.of(Map.of("natural","cliff"),Map.of("highway","path"),
                Map.of("waterway","stream"),Map.of("unknown","yes"))) {
            var fixture = taggedContextFixture(tags,!tags.containsKey("natural"));
            try (var owner = AttemptMemoryLedger.production().rootOwner()) {
                var run = new LegacyBProductionRunner().runVisible(fixture.input(),fixture.visible(),
                        CancellationProbe.NONE,owner,LegacyBPipeline.Deadline.production());
                assertFalse(run.value().assessments().isEmpty(),tags.toString());
                assertTrue(run.value().batch().usage().transitions()>0,"Controls must execute actual B");
                assertTrue(run.value().assessments().stream().allMatch(row ->
                        row.quality().has(FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING)),tags.toString());
                assertTrue(run.value().batch().sections().stream().noneMatch(section ->
                        section.outcome() == LegacyBPipeline.Outcome.B_REFINED),tags.toString());
            }
        }
    }

    static Fixture taggedContextFixture(Map<String,String> tags, boolean witnessed) {
        var fixture = fixture(false,true); var old = fixture.input(); var core = old.network();
        var values = new LinkedHashMap<>(core.primitives());
        var key = PrimitiveKey.existing(PrimitiveKey.Type.WAY,11); var way = (DetachedWay) values.get(key);
        values.put(key,new DetachedWay(key,way.nodeKeys(),tags,false,false));
        // Keep the crossing strictly inside source and context edges, away from lattice vertices.
        for (var nodeKey : way.nodeKeys()) {
            var node = (DetachedNode) values.get(nodeKey);
            var en = Projections.getProjectionByCode("EPSG:3857").latlon2eastNorth(
                    new LatLon(node.coordinate().latitudeDegrees(),node.coordinate().longitudeDegrees()));
            var ll = Projections.getProjectionByCode("EPSG:3857").eastNorth2latlon(new EastNorth(20.17345,en.north()));
            values.put(nodeKey,new DetachedNode(nodeKey,new GeographicPoint(ll.lat(),ll.lon()),node.tags(),false,false));
        }
        var bare = new NetworkSnapshot(core.snapshotId(),core.role(),core.datasetIdentity(),core.sourceGeneration(),
                core.closure(),values,core.incomingReferrerWatches());
        var network = witnessed ? new NetworkSnapshot(bare.snapshotId(),bare.role(),bare.datasetIdentity(),bare.sourceGeneration(),
                bare.closure(),values,bare.incomingReferrerWatches(),new NonTransportSemanticWitness(
                        bare.canonicalHash(),bare.datasetIdentity(),bare.sourceGeneration(),Map.of())) : bare;
        var r = old.request();
        var request = new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),r.settingsHash(),
                r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution());
        return new Fixture(new LegacyBProductionRunner.Input(request,network,old.evidence(),old.fieldName(),old.originalProjected(),
                old.originalMetric(),old.projectionCode(),old.occurrences(),old.frozenRanges(),old.simplify(),old.projectedTolerance()),
                fixture.visible());
    }

    @Test void tinyNativeAllocationKeepsFactualResourceReceiptInsteadOfThrowing() {
        var fixture = fixture(false,false); var tiny = new TraceBudgets(96,1,1,1,1);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var result = assertDoesNotThrow(() -> new LegacyBProductionRunner().runVisible(fixture.input(),fixture.visible(),
                    tiny,CancellationProbe.NONE,owner,LegacyBPipeline.Deadline.production()));
            assertEquals(LegacyBPipeline.Status.RESOURCE_LIMIT,result.value().batch().status());
            assertTrue(result.value().batch().usage().pairVisits() <= tiny.maximumPairVisits());
            assertTrue(result.value().batch().usage().transitions() <= tiny.maximumTransitions());
            assertTrue(result.value().batch().sections().stream().anyMatch(section -> section.execution().isPresent()
                    && section.execution().orElseThrow().run().result().status() == TraceHypothesisSet.Status.RESOURCE_LIMIT));
            result.owner().close(); assertEquals(0,owner.currentBytes());
        }
    }

    @Test void discoveryRunsActualLegacyWithoutBAndExplicitAdmissionOwnsRefinement() {
        var fixture = fixture(false,false); var runner = new LegacyBProductionRunner();
        var deadline = LegacyBPipeline.Deadline.production();
        CancellationProbe rejectEarlyB = () -> Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> frame.getClassName().endsWith(".ProbabilisticTraceEngine"));
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var discovery = assertDoesNotThrow(() -> runner.discoverVisible(fixture.input(), fixture.visible(),
                    rejectEarlyB,owner,deadline));
            assertEquals(3,discovery.value().profileCount());
            assertFalse(discovery.value().seeds().isEmpty());
            assertTrue(discovery.value().alternatives().get(0).rawProjected().stream().anyMatch(point -> point.north() > 2));
            var admitted = List.of(discovery.value().seeds().get(0).id());
            var result = runner.refine(discovery,admitted,fixture.input().request().budgets(),
                    CancellationProbe.NONE,owner,deadline);
            assertTrue(result.value().batch().usage().transitions() > 0);
            assertTrue(result.value().batch().candidates().stream().allMatch(candidate -> admitted.contains(candidate.parentSeedId())));
            assertThrows(IllegalStateException.class, () -> runner.refine(discovery,admitted,
                    fixture.input().request().budgets(),CancellationProbe.NONE,owner,deadline));
            result.owner().close(); assertEquals(0,owner.currentBytes());
        }
    }

    @Test void unadmittedSourceKeepsActualLegacyEvidenceWithoutInventingBWork() {
        var fixture = fixture(false,false); var runner = new LegacyBProductionRunner();
        var deadline = LegacyBPipeline.Deadline.production();
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var discovery = runner.discoverVisible(fixture.input(),fixture.visible(),CancellationProbe.NONE,owner,deadline);
            int omitted = discovery.value().seeds().size(); assertTrue(omitted > 0);
            var result = runner.refine(discovery,List.of(),fixture.input().request().budgets(),
                    CancellationProbe.NONE,owner,deadline);
            assertFalse(result.value().alternatives().isEmpty());
            assertEquals(omitted,result.value().batch().omittedParents());
            assertTrue(result.value().batch().truncated());
            assertTrue(result.value().batch().candidates().isEmpty());
            assertEquals(LegacyBPipeline.Status.RESOURCE_LIMIT,result.value().batch().status());
            assertEquals(TraceWorkUsage.none(),result.value().batch().usage());
            var preview = LegacyBModernResultAdapter.adapt(result.value(),fixture.input(),
                    new ModernTracePipeline.Options(fixture.input().fieldName(),GeometryCleanupConfig.disabled(),"selected",0),
                    CancellationProbe.NONE);
            assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,preview.inference().status());
            assertTrue(preview.routes().isEmpty());
            result.owner().close(); assertEquals(0,owner.currentBytes());
        }
    }

    @Test void cancellationBetweenDiscoveryAndRefinementReleasesTheConsumedDiscovery() {
        var fixture = fixture(false,false); var runner = new LegacyBProductionRunner();
        var deadline = LegacyBPipeline.Deadline.production();
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var discovery = runner.discoverVisible(fixture.input(),fixture.visible(),CancellationProbe.NONE,owner,deadline);
            var admitted = List.of(discovery.value().seeds().get(0).id());
            assertTrue(owner.currentBytes() > 0);
            assertThrows(CancellationException.class, () -> runner.refine(discovery,admitted,
                    fixture.input().request().budgets(),() -> true,owner,deadline));
            assertEquals(0,owner.currentBytes(),"The cancelled attempt must release native discovery data");
        }
    }

    @Test void exhaustedRootAllocationRetainsAdmittedLegacyWithoutCallingB() {
        var fixture = fixture(false,false); var runner = new LegacyBProductionRunner();
        var deadline = LegacyBPipeline.Deadline.production();
        CancellationProbe rejectB = () -> Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> frame.getClassName().endsWith(".ProbabilisticTraceEngine"));
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var discovery = runner.discoverVisible(fixture.input(),fixture.visible(),rejectB,owner,deadline);
            var admitted = List.of(discovery.value().seeds().get(0).id());
            var result = runner.refine(discovery,admitted,fixture.input().request().budgets(),false,
                    rejectB,owner,deadline);
            assertEquals(TraceWorkUsage.none(),result.value().batch().usage());
            assertEquals(1,result.value().batch().candidates().size());
            assertEquals(LegacyBPipeline.Status.RESOURCE_LIMIT,result.value().batch().status());
            assertTrue(result.value().batch().sections().stream().allMatch(section -> section.outcome()
                    == LegacyBPipeline.Outcome.UNATTEMPTED_BUDGET && section.execution().isEmpty()));
            result.owner().close(); assertEquals(0,owner.currentBytes());
        }
    }

    @Test void contextualCrossingWithRemoteEndpointsIsCheckedWithoutProjectingOutsideCertifiedDomain() {
        var fixture = fixture(true, true, Double.POSITIVE_INFINITY, true, 2000);
        var before = fixture.input().network().canonicalHash();
        try (var root = AttemptMemoryLedger.production().rootOwner()) {
            var run = assertDoesNotThrow(() -> new LegacyBProductionRunner().runVisible(
                    fixture.input(), fixture.visible(), CancellationProbe.NONE, root,
                    LegacyBPipeline.Deadline.production()));
            assertFalse(run.value().assessments().isEmpty());
            assertTrue(run.value().assessments().stream().allMatch(assessment ->
                    assessment.quality().has(FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING)),
                    "Clipping remote context must retain the real crossing inside the certified domain");
            assertEquals(before, fixture.input().network().canonicalHash());
        }
    }

    @Test void blankSourceCannotBecomeAnApplicableLegacyOnlyFallback() {
        var fixture = fixture(false, false, Double.POSITIVE_INFINITY, false);
        try (var root = AttemptMemoryLedger.production().rootOwner()) {
            var run = new LegacyBProductionRunner().runVisible(fixture.input(), fixture.visible(),
                    CancellationProbe.NONE, root, LegacyBPipeline.Deadline.production());
            assertTrue(run.value().alternatives().stream()
                    .allMatch(alternative -> alternative.evidence().supportedProfiles() == 0));
            assertTrue(run.value().batch().sections().stream().filter(section -> section.execution().isPresent())
                    .allMatch(section -> section.execution().orElseThrow().eligibleHypothesisIds().isEmpty()));
            assertTrue(run.value().assessments().stream().allMatch(assessment ->
                    assessment.quality().disposition() == FinalGeometryEvaluator.Disposition.HARD_BLOCKED),
                    "A zero-signal fallback must not be confirmable as an applicable slide");
        }
    }

    @Test void actualVisibleLegacyThenBIntervenesBetweenCoarseNativeAnchors() {
        var fixture = fixture(false, false);
        var ledger = AttemptMemoryLedger.production();
        try (var root = ledger.rootOwner()) {
            var accounted = new LegacyBProductionRunner().runVisible(fixture.input(), fixture.visible(),
                    CancellationProbe.NONE, root, LegacyBPipeline.Deadline.production());
            var result = accounted.value();
            assertEquals(3, result.profileCount());
            assertFalse(result.alternatives().isEmpty());
            assertTrue(result.alternatives().get(0).rawProjected().stream().anyMatch(p -> p.north() > 2));
            assertFalse(result.seeds().isEmpty());
            assertTrue(result.batch().sections().stream().anyMatch(outcome -> outcome.execution().isPresent()
                    && outcome.execution().orElseThrow().run().result().evaluatedStates() > 0));
            // Native Legacy sees only x=0,40,80. After restoring fixed endpoints,
            // its coarse line near x=20 is below y=2, while the observed ridge is above y=3.
            assertTrue(result.batch().candidates().stream().filter(candidate -> candidate.sections().stream()
                    .anyMatch(section -> section.source() == LegacyBPipeline.Source.B))
                    .flatMap(candidate -> candidate.points().stream())
                    .anyMatch(point -> point.xMeters() > 18 && point.xMeters() < 22 && point.yMeters() > 2.8),
                    "Bypassing B cannot supply the measured curve between native Legacy vertices");
            assertEquals(result.batch().candidates().size(), result.assessments().size());
            assertEquals("original", result.originalQuality().id());
            assertTrue(result.originalQuality().meanImageCenterCost()
                    > result.assessments().stream().mapToDouble(row -> row.quality().meanImageCenterCost()).min().orElseThrow());
            assertEquals(fixture.input().request().networkContentHash(), fixture.input().network().canonicalHash());
            assertDoesNotThrow(() -> DetachedValueVerifier.verify(result));
            assertTrue(root.currentBytes() > 0);
            accounted.owner().close();
            assertEquals(0, root.currentBytes());
        }
    }

    @Test void localHardBlockedNativeBRetainsLegacyAndStillRunsLaterSection() {
        var fixture = fixture(true, true);
        var ledger = AttemptMemoryLedger.production();
        try (var root = ledger.rootOwner()) {
            var run = new LegacyBProductionRunner().runVisible(fixture.input(), fixture.visible(),
                    CancellationProbe.NONE, root, LegacyBPipeline.Deadline.production());
            var batch = run.value().batch();
            var firstParent = batch.sections().get(0).section().parentSeedId();
            var sections = batch.sections().stream().filter(row -> row.section().parentSeedId().equals(firstParent)).toList();
            assertEquals(2, sections.size());
            assertEquals(LegacyBPipeline.Outcome.LEGACY_RETAINED, sections.get(0).outcome());
            assertEquals(LegacyBPipeline.Reason.NO_USABLE_REFINEMENT, sections.get(0).reason());
            var nativeFirst = sections.get(0).execution().orElseThrow();
            assertFalse(nativeFirst.run().result().hypotheses().isEmpty(), "Keep actual rejected native output");
            assertTrue(nativeFirst.eligibleHypothesisIds().isEmpty());
            assertEquals(LegacyBPipeline.Outcome.B_REFINED, sections.get(1).outcome());
            assertTrue(sections.get(1).execution().orElseThrow().run().usage().transitions() > 0);
            var expected = TraceWorkUsage.none();
            for (var outcome : batch.sections()) {
                if (outcome.execution().isPresent()) expected = expected.plus(outcome.execution().orElseThrow().run().usage());
            }
            assertEquals(expected, batch.usage());
            assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, run.value().originalQuality().disposition());
            assertTrue(run.value().sectionQualities().stream().anyMatch(row -> row.quality()
                    .has(FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING)));
            run.owner().close();
            assertEquals(0, root.currentBytes());
        }
    }

    @Test void expandedImageCoverageDoesNotAuthorizeBToLeaveOriginalEditRegion() {
        var fixture = fixture(false, false, 3.6);
        var ledger = AttemptMemoryLedger.production();
        try (var root = ledger.rootOwner()) {
            var run = new LegacyBProductionRunner().runVisible(fixture.input(), fixture.visible(),
                    CancellationProbe.NONE, root, LegacyBPipeline.Deadline.production());
            var authority = fixture.input().network().closure().editRegion();
            assertFalse(run.value().seeds().isEmpty());
            assertTrue(contained(authority, run.value().seeds().get(0).points()),
                    "The original Legacy alternative must remain inside actual authority");
            boolean outsideNativeOutput = false;
            for (var section : run.value().batch().sections()) {
                if (section.execution().isEmpty()) continue;
                var execution = section.execution().orElseThrow();
                for (var hypothesis : execution.run().result().hypotheses()) {
                    if (!contained(authority, hypothesis.points())) {
                        outsideNativeOutput = true;
                        assertFalse(execution.eligibleHypothesisIds().contains(hypothesis.id()),
                                "Usable imagery outside the edit region cannot authorize a local refinement");
                    }
                }
            }
            assertTrue(outsideNativeOutput, "The counterexample must actually exercise the authority boundary");
            assertTrue(run.value().batch().candidates().stream().allMatch(candidate -> contained(authority, candidate.points())));
        }
    }

    private static boolean contained(MetricRegion region, List<MetricPoint> points) {
        for (int i = 1; i < points.size(); i++)
            if (!region.containsSegment(points.get(i - 1), points.get(i))) return false;
        return true;
    }

    @Test void frozenGeometryIsOriginalAndBDoesNoInference() {
        var fixture = fixture(false, false);
        var old = fixture.input();
        var frozen = new LegacyBProductionRunner.Input(old.request(), old.network(), old.evidence(), old.fieldName(),
                old.originalProjected(), old.originalMetric(), old.projectionCode(), old.occurrences(),
                List.of(old.request().selectedRange()), false, 0);
        var ledger = AttemptMemoryLedger.production();
        try (var root = ledger.rootOwner()) {
            var run = new LegacyBProductionRunner().runVisible(frozen, fixture.visible(), CancellationProbe.NONE,
                    root, LegacyBPipeline.Deadline.production());
            assertFalse(run.value().alternatives().isEmpty(), "Legacy still actually sampled and tracked");
            assertTrue(run.value().alternatives().get(0).rawProjected().stream().anyMatch(p -> p.north() > 2));
            assertEquals(frozen.originalMetric(), run.value().batch().candidates().get(0).points());
            assertTrue(run.value().batch().sections().stream().allMatch(row -> row.outcome() == LegacyBPipeline.Outcome.FROZEN));
            assertEquals(TraceWorkUsage.none(), run.value().batch().usage());
            run.owner().close();
        }
    }

    @Test void cancellationInsideActualBAfterLegacyReleasesAllAttemptOwnedWork() {
        var fixture = fixture(false, false);
        var sawLegacy = new AtomicBoolean();
        CancellationProbe cancellation = () -> {
            var stack = Arrays.stream(Thread.currentThread().getStackTrace()).map(StackTraceElement::getClassName).toList();
            if (stack.contains(RidgeTracker.class.getName())) sawLegacy.set(true);
            return stack.contains("org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine");
        };
        var ledger = AttemptMemoryLedger.production();
        try (var root = ledger.rootOwner()) {
            assertThrows(CancellationException.class, () -> new LegacyBProductionRunner().runVisible(
                    fixture.input(), fixture.visible(), cancellation, root, LegacyBPipeline.Deadline.production()));
            assertTrue(sawLegacy.get());
            assertEquals(0, root.currentBytes());
        }
    }

    @Test void injectedOriginalCoordinatesCannotReplaceFrozenNetworkAuthority() {
        var fixture = fixture(false, false);
        var old = fixture.input();
        var altered = new ArrayList<>(old.originalMetric());
        altered.set(1, new MetricPoint(40, 7));
        assertThrows(IllegalArgumentException.class, () -> new LegacyBProductionRunner.Input(old.request(), old.network(),
                old.evidence(), old.fieldName(), old.originalProjected(), altered, old.projectionCode(),
                old.occurrences(), List.of(), false, 0));
        var twoDetectors = new LegacyVisibleProfileSampler.Input(fixture.visible().raster(), fixture.visible().anchors(),
                List.of("hot", "blue"), fixture.visible().options());
        var ledger = AttemptMemoryLedger.production();
        try (var root = ledger.rootOwner()) {
            assertThrows(IllegalArgumentException.class, () -> new LegacyBProductionRunner().runVisible(old, twoDetectors,
                    CancellationProbe.NONE, root, LegacyBPipeline.Deadline.production()));
            assertEquals(0, root.currentBytes());
        }
    }

    record Fixture(LegacyBProductionRunner.Input input, LegacyVisibleProfileSampler.Input visible) { }

    static Fixture fixture(boolean fixedMiddle, boolean crossing) {
        return fixture(fixedMiddle, crossing, Double.POSITIVE_INFINITY);
    }

    private static Fixture fixture(boolean fixedMiddle, boolean crossing, double authorizedHalfWidth) {
        return fixture(fixedMiddle, crossing, authorizedHalfWidth, true);
    }

    private static Fixture fixture(boolean fixedMiddle, boolean crossing, double authorizedHalfWidth, boolean signal) {
        return fixture(fixedMiddle, crossing, authorizedHalfWidth, signal, 10);
    }

    private static Fixture fixture(boolean fixedMiddle, boolean crossing, double authorizedHalfWidth, boolean signal,
            double contextExtent) {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var projected = List.of(new ProjectedPoint(0, 0), new ProjectedPoint(40, 0), new ProjectedPoint(80, 0));
        var keys = List.of(nodeKey(1), nodeKey(2), nodeKey(3));
        var way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10);
        var values = new LinkedHashMap<PrimitiveKey, DetachedPrimitive>();
        for (int i = 0; i < projected.size(); i++) {
            var ll = projection.eastNorth2latlon(new EastNorth(projected.get(i).east(), projected.get(i).north()));
            values.put(keys.get(i), new DetachedNode(keys.get(i), new GeographicPoint(ll.lat(), ll.lon()), Map.of(), false, false));
        }
        values.put(way, new DetachedWay(way, keys, Map.of("highway", "path"), false, false));
        if (crossing) {
            for (int i = 0; i < 2; i++) {
                var ll = projection.eastNorth2latlon(new EastNorth(20, i == 0 ? -contextExtent : contextExtent));
                values.put(nodeKey(4 + i), new DetachedNode(nodeKey(4 + i),
                        new GeographicPoint(ll.lat(), ll.lon()), Map.of(), false, false));
            }
            var crossWay = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 11);
            values.put(crossWay, new DetachedWay(crossWay, List.of(nodeKey(4), nodeKey(5)), Map.of(), false, false));
        }
        int width = 720, height = 240;
        int[] pixels = new int[width * height];
        double[] scalar = new double[pixels.length]; boolean[] valid = new boolean[pixels.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double east = -20 + x / 6.0, north = 20 - y / 6.0;
            double ridge = fixedMiddle ? 0 : 3 + Math.sin(Math.PI * Math.max(0, Math.min(80, east)) / 80);
            int value = signal ? (int) Math.round(255 * (.02 + .90 * Math.exp(-Math.pow((north - ridge) / .65, 2) / 2))) : 0;
            pixels[y * width + x] = 0xFF000000 | value << 16 | value << 8 | value;
            scalar[y * width + x] = value / 255.0;
        }
        var raster = new LiveBPreviewService.VisibleRaster(width, height, pixels, -20, -20, 100, 20,
                1, 1, OptionalDouble.of(1), "synthetic-runner", "EPSG:3857");
        var origin = metric(frame, -20, 20);
        var xStep = metric(frame, -20 + 1.0 / 6, 20);
        var yStep = metric(frame, -20, 20 - 1.0 / 6);
        var transform = new RasterMetricTransform("runner-raster",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, origin,
                xStep.xMeters() - origin.xMeters(), xStep.yMeters() - origin.yMeters(),
                yStep.xMeters() - origin.xMeters(), yStep.yMeters() - origin.yMeters(), 1);
        var footprint = new MetricRegion(List.of(List.of(transform.pixelCenterToMetric(-.5, -.5),
                transform.pixelCenterToMetric(width - .5, -.5),
                transform.pixelCenterToMetric(width - .5, height - .5),
                transform.pixelCenterToMetric(-.5, height - .5))));
        var resolution = EvidenceResolution.nativeSource(1, 1);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "hot", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var evidence = new EvidenceSnapshot("runner-evidence", frame, transform, resolution,
                footprint, footprint, Map.of("hot", new ScalarEvidenceField(width, height, scalar, valid, lineage)),
                "synthetic-runner");
        var protectedKeys = fixedMiddle ? Set.copyOf(keys) : Set.of(keys.get(0), keys.get(2));
        var editRegion = Double.isFinite(authorizedHalfWidth)
                ? new MetricRegion(List.of(List.of(new MetricPoint(-20, -authorizedHalfWidth),
                        new MetricPoint(100, -authorizedHalfWidth), new MetricPoint(100, authorizedHalfWidth),
                        new MetricPoint(-20, authorizedHalfWidth)))) : footprint;
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY, "runner-test", values.keySet(),
                Set.of(way), Set.of(), protectedKeys, Set.of(), Map.of(way, List.of(new OccurrenceRange(0, 2))),
                List.of(), footprint, editRegion, true, true, true, true);
        var network = new NetworkSnapshot("runner-network", SnapshotRole.CAPTURED_BEFORE, "synthetic", 1,
                closure, values, V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var original = keys.stream().map(key -> frame.toMetric(((DetachedNode) values.get(key)).coordinate())).toList();
        var request = new TraceRequest(way, new OccurrenceRange(0, 2), TrackerMode.LEGACY_THEN_B,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "legacy-then-b-v1", "synthetic", 2,
                new ProbabilisticProfileFactory().profileChainage(original, 2), resolution);
        var facts = List.of(new Occurrence(0, true, true), new Occurrence(1, fixedMiddle, fixedMiddle),
                new Occurrence(2, true, true));
        var input = new LegacyBProductionRunner.Input(request, network, evidence, "hot", projected, original,
                "EPSG:3857", facts, List.of(), false, 0);
        var anchors = new ArrayList<LegacyVisibleProfileSampler.Anchor>();
        for (int i = 0; i < projected.size(); i++) anchors.add(new LegacyVisibleProfileSampler.Anchor(projected.get(i),
                new RasterPoint((projected.get(i).east() + 20) * 6, 120), original.get(i).xMeters()));
        return new Fixture(input, new LegacyVisibleProfileSampler.Input(raster, anchors, List.of("hot"),
                new LegacyVisibleProfileSampler.Options(4, 1, 6, IntensitySamplingMode.DIRECT_VALUE)));
    }

    private static PrimitiveKey nodeKey(long id) { return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id); }
    private static MetricPoint metric(LocalMetricFrame frame, double east, double north) {
        var ll = Projections.getProjectionByCode("EPSG:3857").eastNorth2latlon(new EastNorth(east, north));
        return frame.toMetric(new GeographicPoint(ll.lat(), ll.lon()));
    }
}
