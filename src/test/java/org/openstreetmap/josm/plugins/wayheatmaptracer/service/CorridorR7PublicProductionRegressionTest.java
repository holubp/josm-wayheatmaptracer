package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.invokeOwned;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.withPolicy;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.withoutReservation;

import java.awt.geom.Line2D;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Portable retained public R4/R7 regression; all final routes remain independently reviewable. */
class CorridorR7PublicProductionRegressionTest {
    @org.junit.jupiter.api.io.TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path output;
    private final List<String> failures = new ArrayList<>();
    private long diagnosticAuthorityCalls;

    @Test void fixedStraightPublicPairMustPreserveMeasuredShapeAndOriginalGates() throws Exception {
        System.out.println("R7 portable retained evidence: " + output.toAbsolutePath());
        try {
            var r4 = new CorridorObservedShoulderCompositionTest().field(false);
            var r7 = withPolicy(r4, CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1);
            assertEquals(r4.request(), withPolicy(r7, CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1).request());
            assertSame(r4.evidence(), r7.evidence()); assertSame(r4.network(), r7.network());
            write("frozen-inputs-before-dp.txt", "R4=" + r4.request() + "\nR7=" + r7.request()
                + "\nevidence=" + r4.evidence().canonicalHash() + "\nnetwork=" + r4.network().canonicalHash()
                + "\nsource=UNCHANGED CorridorObservedShoulderCompositionTest.field(false)"
                + "\noptimizerRequests=one_R4_then_one_R7_raw_and_one_R7_final_pipeline; no refitter/counterfactual/sweep"
                + "\nphysicalWindow=60..64; adjacent=59..65; target=original62; targetSigmaMeters=.5; denseStepMeters=.25");
            long started = System.nanoTime(); var old = invokeOwned(r4); long oldNanos = System.nanoTime() - started;
            dump("R4", old, r4, oldNanos);
            assertCompleted(old, "R4");
            var oldTarget = target(old.tracked(), r4.evidence());
            var beforeResult = old.tracked().optimizations().get(oldTarget.id());
            var before = points(beforeResult, r4.evidence());
            write("R4-target-native101.txt", before);
            assertEquals(101, before.size());
            assertEquals(expectedNative(), before, "Exact original101 physical points, not archive hash self-consistency");
            assertEquals(1.8469026957, physicalRms(before, 60, 64), 1e-8);
            assertEquals(60.9941553176, metricPeak(before, 60, 64), 1e-8);
            var band = oldTarget.points().get(62).band(); double selected = beforeResult.offsetsPx().get(62);
            assertTrue(selected < band.shoulderMinPx() || selected > band.shoulderMaxPx(), "Known original row62 off-shoulder failure must reproduce");
            assertFalse(old.tracker().correspondencePreparations().get(oldTarget.id()).admitted());

            var shapeControls = preassertShapeControls(old, r4.evidence());
            write("frozen-shape-controls-before-R7.txt", shapeControls);
            started = System.nanoTime(); var current = invokeOwned(r7); long newNanos = System.nanoTime() - started;
            dump("R7", current, r7, newNanos);
            assertCompleted(current, "R7");
            var newTarget = target(current.tracked(), r7.evidence());
            var afterResult = current.tracked().optimizations().get(newTarget.id());
            var after = points(afterResult, r7.evidence());
            write("R7-target-native101.txt", after);
            check(oldTarget.id().equals(newTarget.id()), "TARGET_TRACK_ID_CHANGED");
            check(after.size() == 101, "TARGET_NO_LONGER_ORIGINAL101");
            check(current.tracker().executedCostPolicies().get(newTarget.id()) == CorridorCenterlineOptimizer.OriginalCostPolicy.DIRECT_MEASURED_CORE_V2,
                "TARGET_EFFECTIVE_POLICY_NOT_DIRECT");
            var a = old.tracked(); var b = current.tracked();
            check(a.profiles().equals(b.profiles()), "PROFILE_INPUT_CHANGED");
            check(a.tracks().equals(b.tracks()), "TRACK_OR_RAW_OWNERSHIP_CHANGED");
            check(a.tubes().equals(b.tubes()), "TUBE_CHANGED");
            check(a.scaleEvidence().equals(b.scaleEvidence()), "SCALE_EVIDENCE_CHANGED");
            check(a.multiScaleProfiles().equals(b.multiScaleProfiles()), "MULTISCALE_INPUT_CHANGED");
            check(a.sparseBundles().equals(b.sparseBundles()), "SPARSE_PROVENANCE_CHANGED");
            check(a.groupingDecisions().equals(b.groupingDecisions()), "GROUPING_CHANGED");
            check(a.sourcePixelSizePx() == b.sourcePixelSizePx(), "SOURCE_PITCH_CHANGED");
            write("frozen-R4-generated-inputs.txt", "profiles=" + a.profiles() + "\ntracks=" + a.tracks()
                + "\ntubes=" + a.tubes() + "\nscale=" + a.scaleEvidence() + "\nmultiscale=" + a.multiScaleProfiles());

            wholeRoutes(old, current, r4);
            finalRoutesAndParentComposition(old, current, r7);
            check(old.run().usage().pairVisits() == current.run().usage().pairVisits(), "NATIVE_PAIR_WORK_CHANGED");
            check(old.run().usage().transitions() == current.run().usage().transitions(), "NATIVE_TRANSITION_WORK_CHANGED");
            check(old.run().corridorReceipt().orElseThrow().totals().equals(current.run().corridorReceipt().orElseThrow().totals()), "NATIVE_TOTAL_WORK_CHANGED");
            double oldRms = physicalRms(before, 60, 64), newRms = physicalRms(after, 60, 64);
            double oldPeak = metricPeak(before, 60, 64), newPeak = metricPeak(after, 60, 64), adjacent = metricPeak(after, 59, 65);
            check(newRms <= .60 * oldRms, "RMS_40_PERCENT_GATE");
            check(newPeak <= .75 * oldPeak, "PEAK_25_PERCENT_GATE");
            check(adjacent < oldPeak, "ADJACENT_59_65_TRANSFER_GATE");
            double targetResidual = Math.abs(after.get(62).yMeters() - upper(after.get(62).xMeters()));
            check(targetResidual <= .5 + 1e-9, "TARGET62_OUTSIDE_FACTUAL_HALF_METRE_SIGMA");
            var image = ImageCostField.fromEvidence(r4.evidence(), "native");
            var freshTarget = image.freezeProfile(after.get(62), tangent(after, 62));
            check(freshTarget.support() == ImageCostField.FrozenSupport.MEASURED, "TARGET62_FRESH_IMAGE_NOT_MEASURED");
            shapeGates(shapeControls, old, current, r4.evidence(), image);
            int denseFailures = denseSameFraction(before, after, image);
            check(denseFailures == 0, "FINAL_DENSE_IMAGE_ANALYTIC_FIT:" + denseFailures);
            write("summary.txt", "PORTABLE_PUBLIC_PRESERVATION; not complete release qualification"
                + "\nactualProductionRequestCalls=3; refitterCalls=0; extraOptimizerCalls=0"
                + "\nR4Target=" + oldTarget.id() + "\nR7Target=" + newTarget.id()
                + "\nphysicalRms60..64=" + oldRms + " -> " + newRms + "\nwrappedMetricPeak60..64=" + oldPeak + " -> " + newPeak
                + "\nwrappedMetricPeak59..65=" + adjacent + "\ncompositionOffsetSecondDifferenceRms60..64="
                + offsetRms(beforeResult.offsetsPx(), 60, 64) + " -> " + offsetRms(afterResult.offsetsPx(), 60, 64)
                + "\ncompositionUnwrappedOffsetTurn60..64=" + offsetPeak(beforeResult.offsetsPx(), 60, 64) + " -> " + offsetPeak(afterResult.offsetsPx(), 60, 64)
                + "\ntarget62AnalyticResidualMeters=" + targetResidual + " fixedSigma=.5 freshSigma=" + freshTarget.localizationSigmaMeters()
                + "\ndenseFailures=" + denseFailures + "\nextraReadOnlyDiagnosticAuthorityCalls=" + diagnosticAuthorityCalls
                + "\nR4ElapsedNanos=" + oldNanos + " R7ElapsedNanos=" + newNanos
                + "\nR4NativeWork=" + old.run().usage() + "\nR7NativeWork=" + current.run().usage()
                + "\nfailures=" + failures + "\nFrozenRefitMeshFit=UNVERIFIED_NOT_IN_THIS_EARLY_SUBSET; no rope/refitter dependency");
            assertTrue(failures.isEmpty(), "FIXED PUBLIC CANDIDATE STOP: " + failures);
        } catch (Exception | AssertionError failure) {
            write("terminal-failure.txt", failure.toString()); throw failure;
        } finally {
            writeDiagnostic("accumulated-failures.txt", failures);
        }
    }


    /** Six enumeration counters are invariant; strict objective-improvement allocations are bounded. */
    private static long[] workVector(CorridorCenterlineOptimizer.OptimizationResult r) {
        return new long[] { r.maximumOffsetStates(), r.maximumPairStates(), r.profileCostEvaluations(),
            r.transitionEvaluations(), r.pointTableEntries(), r.adjacentGeometryEntries(), r.retainedPairStateAllocations() };
    }
    private static boolean staticWorkCompatible(long[] a, long[] b, boolean samePolicy) {
        if (a.length != 7 || b.length != 7) return false;
        for (int i = 0; i < 6; i++) if (a[i] != b[i]) return false;
        return a[6] >= 0 && b[6] >= 0 && a[6] <= a[3] && b[6] <= b[3] && (!samePolicy || a[6] == b[6]);
    }
    @Test void workComparatorRejectsEachChangedEnumerationAndInvalidAllocation() {
        long[] baseline = { 9, 81, 909, 6400, 909, 8100, 3200 };
        assertTrue(staticWorkCompatible(baseline, baseline.clone(), true));
        for (int field = 0; field < 6; field++) {
            var changed = baseline.clone(); changed[field]++;
            assertFalse(staticWorkCompatible(baseline, changed, false), "enumeration field " + field);
        }
        var changed = baseline.clone(); changed[6]++;
        assertTrue(staticWorkCompatible(baseline, changed, false));
        assertFalse(staticWorkCompatible(baseline, changed, true), "same policy allocation must remain exact");
        changed[6] = baseline[3] + 1;
        assertFalse(staticWorkCompatible(baseline, changed, false));
        changed[6] = -1;
        assertFalse(staticWorkCompatible(baseline, changed, false));
    }

    private void finalRoutesAndParentComposition(CorridorDirectPositionCostPolicyTest.Owned old,
            CorridorDirectPositionCostPolicyTest.Owned current, CorridorPolicyFixture.Fixture f) throws Exception {
        var pipeline = new ModernTracePipeline(new CorridorEngineAdapter("native")).runWithUsage(
            f.request(), f.evidence(), f.network(), f.options(), CancellationProbe.NONE);
        check(current.run().result().equals(pipeline.result().inference()), "SAME_POLICY_PIPELINE_RAW_OUTPUT_CHANGED");
        check(current.run().usage().equals(pipeline.usage()), "SAME_POLICY_PIPELINE_WORK_CHANGED");
        check(current.run().corridorReceipt().orElseThrow().totals().equals(pipeline.corridorReceipt().orElseThrow().totals()), "SAME_POLICY_PIPELINE_TOTALS_CHANGED");
        check(pipeline.result().routes().size() == 6, "ALL_SIX_FINAL_ROUTES_REQUIRED");
        check(current.tracked().optimizations().size() == 6, "ALL_SIX_RAW_ROUTES_REQUIRED");
        var image = ImageCostField.fromEvidence(f.evidence(), "native");
        var authority = CorridorWorkingSchedule.nativeAuthority(f.evidence(), "native", CancellationProbe.NONE);
        var details = new StringBuilder(); var freshRows = new StringBuilder("route\trow\tx\ty\tsupport\tcoreDistance\tsigma\tenergy\tdisposition\n");
        try {
        for (var route : pipeline.result().routes()) {
            check(route.assignments().size() == route.pointIds().size(), "FINAL_ASSIGNMENT_COUNT:" + route.hypothesis().id());
            check(route.sourceOwnership().keySet().equals(new HashSet<>(route.pointIds())), "FINAL_OWNERSHIP_COUNT:" + route.hypothesis().id());
            var points = route.hypothesis().points();
            details.append("FINAL ").append(route).append('\n');
            finalIdentitiesAndGeometry(route, f, details);
            var support = FinalGeometryEvaluator.inspectSupport(points, image, 1.);
            check(Math.abs(support.totalLengthMeters() - route.quality().totalLengthMeters()) <= 1e-9,
                "FINAL_FACTUAL_TOTAL_LENGTH:" + route.hypothesis().id());
            check(Math.abs(support.directlySupportedLengthMeters() - route.quality().directlySupportedLengthMeters()) <= 1e-9,
                "FINAL_FACTUAL_DIRECT_LENGTH:" + route.hypothesis().id());
            check(Math.abs(support.worstUnsupportedSpanMeters() - route.quality().worstUnsupportedSpanMeters()) <= 1e-9,
                "FINAL_FACTUAL_UNSUPPORTED_SPAN:" + route.hypothesis().id());
            check(Double.compare(support.meanImageCenterCost(), route.quality().meanImageCenterCost()) == 0,
                "FINAL_FACTUAL_IMAGE_COST:" + route.hypothesis().id());
            boolean insufficient = support.directlySupportedLengthMeters() + 1e-9 < .95 * support.totalLengthMeters()
                || support.worstUnsupportedSpanMeters() > 10.;
            check(insufficient == route.quality().has(FinalGeometryEvaluator.FindingCode.INSUFFICIENT_DIRECT_SUPPORT),
                "FINAL_SUPPORT_DISPOSITION_CONTRACT:" + route.hypothesis().id());
            boolean hard = route.quality().findings().stream().anyMatch(value -> value.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK);
            var expectedDisposition = hard ? FinalGeometryEvaluator.Disposition.HARD_BLOCKED : route.quality().findings().isEmpty()
                ? FinalGeometryEvaluator.Disposition.APPLICABLE : FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED;
            check(expectedDisposition == route.quality().disposition(), "FINAL_FINDING_DISPOSITION_CONTRACT:" + route.hypothesis().id());
            details.append("FRESH_INTEGRATED_SUPPORT ").append(support).append('\n');
            for (int row = 0; row < points.size(); row++) {
                var point = points.get(row); var direction = tangent(points, row);
                var fresh = image.freezeProfile(point, direction); var scalar = image.sampleRoute(point, direction);
                double distance = Math.max(0, Math.max(fresh.coreMinimumMeters(), -fresh.coreMaximumMeters()));
                check(point.equals(route.assignments().get(route.pointIds().get(row))), "FINAL_ASSIGNMENT_VALUE:" + row);
                freshRows.append(route.hypothesis().id()).append('\t').append(row).append('\t').append(point.xMeters()).append('\t').append(point.yMeters())
                    .append('\t').append(fresh.support()).append('\t').append(distance).append('\t').append(fresh.localizationSigmaMeters())
                    .append('\t').append(scalar.map(ImageCostField.RouteSample::imageEnergy).orElse(Double.NaN)).append('\t').append(route.quality().disposition()).append('\n');
                if (route.quality().disposition() == FinalGeometryEvaluator.Disposition.APPLICABLE) {
                    check(route.quality().findings().isEmpty(), "APPLICABLE_WITH_FINDINGS:" + route.hypothesis().id());
                }
            }
            if (route.quality().disposition() == FinalGeometryEvaluator.Disposition.APPLICABLE) {
                check(!insufficient && Double.isFinite(support.meanImageCenterCost()), "APPLICABLE_WITHOUT_FACTUAL_SUPPORT:" + route.hypothesis().id());
                check(authorityFailures(points, authority).isEmpty(), "APPLICABLE_AUTHORITY_FAILURE:" + route.hypothesis().id());
                check(contacts(points).isEmpty() && FinalGeometryEvaluator.backtrackFindings(points).isEmpty(), "APPLICABLE_TOPOLOGY_FAILURE:" + route.hypothesis().id());
            }
            var rawPoints = route.rawHypothesis().points();
            check(points.get(0).equals(rawPoints.get(0)) && points.get(points.size()-1).equals(rawPoints.get(rawPoints.size()-1)), "FINAL_PROTECTED_ENDPOINT_CHANGED");
        }
        int compositionParents = 0;
        for (var parent : current.tracked().tracks()) {
            if (!parent.parent() || !parent.points().keySet().containsAll(List.of(84,85,86))) continue;
            if (parent.points().get(84).band().nestedCentersPx().size() != 3
                || parent.points().get(86).band().nestedCentersPx().size() != 2) continue;
            compositionParents++;
            var bundle = current.tracked().sparseBundles().stream().filter(value -> value.id().equals(parent.id())).findFirst().orElseThrow();
            check(parent.childTrackIds().stream().allMatch(id -> current.tracked().tracks().stream().anyMatch(t -> !t.parent() && t.id().equals(id))), "PARENT_HID_CHILD:" + parent.id());
            var before = old.tracked().optimizations().get(parent.id()); var after = current.tracked().optimizations().get(parent.id());
            for (int row : List.of(84,85,86)) {
                var point = parent.points().get(row); var provenance = bundle.points().get(row);
                check(provenance != null, "PARENT_MISSING_FACTUAL_PROVENANCE:" + row);
                details.append("PARENT_COMPOSITION row=").append(row).append(" track=").append(parent.id()).append(" band=").append(point.band())
                    .append(" provenance=").append(provenance).append(" R4offset=").append(before.offsetsPx().get(row)).append(" R7offset=").append(after.offsetsPx().get(row))
                    .append(" R7cost=").append(after.costs().get(row)).append('\n');
                if (provenance.directContributorTrackIds().isEmpty()) {
                    check(point.support() == CorridorPointSupport.BOUNDED_INTERPOLATION, "PREDICTED_PARENT_BECAME_DIRECT:" + row);
                    check(point.band().peakIntensity() == 0 && point.band().signalExistenceConfidence() == 0, "PREDICTED_PARENT_INVENTED_SIGNAL:" + row);
                }
            }
            check(!bundle.points().get(84).directContributorTrackIds().equals(bundle.points().get(86).directContributorTrackIds()), "COMPOSITION_CONTROL_NEEDS_CHANGING_DIRECT_MEMBERSHIP");
        }
        check(compositionParents > 0, "REAL_MULTIROW_PARENT_COMPOSITION_REQUIRED");
        } finally {
            writeDiagnostic("all-final-routes-and-parent-composition.txt", details);
            writeDiagnostic("all-final-fresh-scalar.tsv", freshRows);
            writeDiagnostic("accumulated-failures.txt", failures);
        }
    }

    /** Checks retained identities and geometric coverage without reconstructing seedGeometry's fraction union. */
    private void finalIdentitiesAndGeometry(ModernTracePipeline.Route route, CorridorPolicyFixture.Fixture f,
            StringBuilder details) {
        var selected = (DetachedWay) f.network().primitives().get(f.request().selectedWayKey());
        var raw = route.rawHypothesis(); var points = route.hypothesis().points();
        check(raw.points().size() == 101, "RAW_FULL_NATIVE_ROWS:" + raw.id());
        check(new HashSet<>(route.pointIds()).size() == route.pointIds().size(), "FINAL_DUPLICATE_TYPED_ID:" + raw.id());
        var existing = new LinkedHashMap<Integer, FinalRoutePointId.ExistingWayNodeOccurrence>();
        int previousExisting = -1, previousGenerated = -1;
        double previousX = Double.NEGATIVE_INFINITY;
        for (int row = 0; row < route.pointIds().size(); row++) {
            var id = route.pointIds().get(row); var point = points.get(row);
            details.append("FINAL_ID row=").append(row).append(" id=").append(id).append(" point=").append(point)
                .append(" sourceOwnership=").append(route.sourceOwnership().get(id)).append('\n');
            check(point.xMeters() + 1e-9 >= previousX, "FINAL_GEOMETRIC_ORDER:" + raw.id() + ":" + row);
            previousX = point.xMeters();
            check(distanceToPolyline(point, raw.points()) <= 1e-9, "FINAL_LEFT_RAW_GEOMETRY:" + raw.id() + ":" + row);
            if (id instanceof FinalRoutePointId.GeneratedCandidatePoint generated) {
                int original = generated.originalPointIndex();
                check(generated.candidateId().equals(raw.id()), "GENERATED_CANDIDATE_ID:" + id);
                check(original > previousGenerated, "GENERATED_RAW_ORDER:" + id); previousGenerated = original;
                check(original < raw.points().size(), "GENERATED_RAW_INDEX:" + id);
                if (original < raw.points().size()) {
                    check(point.equals(raw.points().get(original)), "GENERATED_RAW_COORDINATE:" + id);
                    check(route.sourceOwnership().get(id) == raw.support().get(original), "GENERATED_RAW_OWNERSHIP:" + id);
                }
            } else if (id instanceof FinalRoutePointId.ExistingWayNodeOccurrence occurrence) {
                int original = occurrence.originalOccurrenceIndex();
                check(original > previousExisting, "EXISTING_OCCURRENCE_ORDER:" + id); previousExisting = original;
                check(existing.put(original, occurrence) == null, "DUPLICATE_EXISTING_OCCURRENCE:" + id);
                check(occurrence.wayKey().equals(f.request().selectedWayKey()), "EXISTING_WAY_ID:" + id);
                check(original >= f.request().selectedRange().firstIndex() && original <= f.request().selectedRange().lastIndex(), "EXISTING_OCCURRENCE_RANGE:" + id);
                if (original < selected.nodeKeys().size()) check(occurrence.nodeKey().equals(selected.nodeKeys().get(original)), "EXISTING_NODE_ID:" + id);
            }
        }
        check(existing.size() == 101, "ALL_EXISTING_OCCURRENCES_REQUIRED:" + raw.id());
        for (int original = 0; original <= 100; original++) check(existing.containsKey(original), "MISSING_EXISTING_OCCURRENCE:" + raw.id() + ":" + original);
        for (var point : raw.points()) check(points.stream().anyMatch(finalPoint -> finalPoint.distanceTo(point) <= 1e-9), "RAW_POINT_NOT_RETAINED:" + raw.id() + ":" + point);
        for (int endpoint : List.of(0,100)) {
            var id = existing.get(endpoint); check(id != null, "MISSING_FIXED_ENDPOINT_ID:" + raw.id() + ":" + endpoint);
            if (id == null) continue;
            var captured = (DetachedNode) f.network().primitives().get(selected.nodeKeys().get(endpoint));
            var fixed = f.evidence().coordinateFrame().toMetric(captured.coordinate());
            check(fixed.equals(route.assignments().get(id)), "FIXED_CAPTURED_ENDPOINT_COORDINATE:" + id);
            check(route.sourceOwnership().get(id) == ObservationOwnership.FIXED_TOPOLOGY_ONLY, "FIXED_CAPTURED_ENDPOINT_OWNERSHIP:" + id);
            check(route.pointIds().get(endpoint == 0 ? 0 : route.pointIds().size()-1).equals(id), "FIXED_ENDPOINT_TYPED_POSITION:" + id);
        }
        details.append("FINAL_IDENTITY_COUNTS raw=").append(raw.points().size()).append(" final=").append(points.size())
            .append(" existing=").append(existing.size()).append(" generated=").append(route.pointIds().size()-existing.size()).append('\n');
    }

    private static double distanceToPolyline(MetricPoint point, List<MetricPoint> polyline) {
        double distance = Double.POSITIVE_INFINITY;
        for (int row = 1; row < polyline.size(); row++) {
            var a = polyline.get(row-1); var b = polyline.get(row);
            double dx = b.xMeters() - a.xMeters(), dy = b.yMeters() - a.yMeters();
            double px = point.xMeters() - a.xMeters(), py = point.yMeters() - a.yMeters();
            double lengthSquared = dx * dx + dy * dy;
            double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, (px * dx + py * dy) / lengthSquared));
            distance = Math.min(distance, Math.hypot(px - t * dx, py - t * dy));
        }
        return distance;
    }

    @Test void stableDistanceRetainsActualCollinearPointAndRejectsPerpendicularDeparture() {
        // Literal raw segment and final point retained in fix-round1-temp-evidence, not a new trace.
        var a = new MetricPoint(26.000000000022137, 41.057481617199635);
        var b = new MetricPoint(27.999999999974854, 42.06810482692629);
        var q = new MetricPoint(26.099916262456023, 41.10797046412324);
        assertTrue(distanceToPolyline(q, List.of(a,b)) <= 1e-9);
        double dx = b.xMeters()-a.xMeters(), dy = b.yMeters()-a.yMeters(), length = Math.hypot(dx,dy);
        var departed = new MetricPoint(q.xMeters()-dy/length*1e-8, q.yMeters()+dx/length*1e-8);
        assertTrue(distanceToPolyline(departed, List.of(a,b)) > 1e-9, "A real perpendicular departure must still fail");
        assertEquals(Math.sqrt(2), distanceToPolyline(new MetricPoint(2,1), List.of(new MetricPoint(0,0),new MetricPoint(1,0))), 1e-15);
        assertEquals(5., distanceToPolyline(new MetricPoint(3,4), List.of(new MetricPoint(0,0),new MetricPoint(0,0))));
    }

    private void assertCompleted(CorridorDirectPositionCostPolicyTest.Owned owned, String label) {
        assertNotNull(owned.tracked(), label + " actual native tracking unavailable");
        assertEquals("COMPLETE", owned.run().corridorReceipt().orElseThrow().certificateStatus(), label + " source certificate");
        assertTrue(Set.of(TraceHypothesisSet.Status.COMPLETE, TraceHypothesisSet.Status.AMBIGUOUS).contains(owned.run().result().status()),
            label + " cancellation/resource/no-route cannot count as quality success");
    }
    private static CorridorTrack target(CorridorAwareTracker.TrackingResult result, EvidenceSnapshot evidence) {
        double expectedY = upper(144.);
        var found = result.tracks().stream().filter(t -> !t.parent() && t.points().containsKey(62))
            .min(Comparator.comparingDouble(t -> Math.abs(metricY(result.profiles().get(62), t.points().get(62).band().centerOffsetPx(), evidence) - expectedY))).orElseThrow();
        assertTrue(Math.abs(metricY(result.profiles().get(62), found.points().get(62).band().centerOffsetPx(), evidence) - expectedY) <= 1.,
            "Actual direct analytic upper strand required, never a supplied/generated ID");
        return found;
    }
    private void dump(String label, CorridorDirectPositionCostPolicyTest.Owned owned, CorridorPolicyFixture.Fixture f, long nanos) throws Exception {
        write(label + "-native-run.txt", "elapsedNanos=" + nanos + "\nrun=" + owned.run()
            + "\neffectivePolicies=" + (owned.tracker() == null ? "unavailable" : owned.tracker().executedCostPolicies())
            + "\npolicyMetadataBytes=" + (owned.tracker() == null ? "unavailable" : owned.tracker().executedCostPolicyMetadataBytes())
            + "\nwork=" + (owned.tracker() == null ? "unavailable" : owned.tracker().correspondenceWork()));
        if (owned.tracked() == null) return;
        var text = new StringBuilder();
        for (var track : owned.tracked().tracks()) {
            var result = owned.tracked().optimizations().get(track.id());
            text.append("TRACK ").append(track).append("\nRESULT ").append(result).append('\n');
            if (result != null) text.append("METRIC ").append(points(result, f.evidence())).append('\n');
        }
        text.append("CANDIDATES ").append(owned.tracked().candidates());
        write(label + "-all-native-routes-and-children.txt", text);
    }
    private void wholeRoutes(CorridorDirectPositionCostPolicyTest.Owned old, CorridorDirectPositionCostPolicyTest.Owned current,
            CorridorPolicyFixture.Fixture f) throws Exception {
        var authority = CorridorWorkingSchedule.nativeAuthority(f.evidence(), "native", CancellationProbe.NONE);
        var triples = new StringBuilder("track\tworkingRow\toriginalRow\tR4TurnDegrees\tR7TurnDegrees\tR4SecondDifferenceMeters\tR7SecondDifferenceMeters\n");
        var guards = new StringBuilder();
        long retainedMetadata = 0;
        check(old.tracked().optimizations().keySet().equals(current.tracked().optimizations().keySet()), "RAW_ROUTE_INVENTORY_CHANGED");
        for (var track : old.tracked().tracks()) {
            var beforeResult = old.tracked().optimizations().get(track.id()); var afterResult = current.tracked().optimizations().get(track.id());
            if (beforeResult == null || afterResult == null) { check(beforeResult == afterResult, "RAW_ROUTE_MISSING:" + track.id()); continue; }
            var before = points(beforeResult, f.evidence()); var after = points(afterResult, f.evidence());
            check(before.size() == after.size(), "RAW_ROUTE_ROWS_CHANGED:" + track.id());
            check(staticWorkCompatible(workVector(beforeResult), workVector(afterResult), false), "RAW_NATIVE_WORK_CHANGED:" + track.id());
            guards.append(track.id()).append(" work R4=").append(Arrays.toString(workVector(beforeResult)))
                .append(" R7=").append(Arrays.toString(workVector(afterResult))).append('\n');
            if (before.isEmpty() || after.isEmpty()) continue;
            var oldPrepared = old.tracker().preparedOptimization(track.id()).orElse(null);
            var newPrepared = current.tracker().preparedOptimization(track.id()).orElse(null);
            var policy = current.tracker().executedCostPolicies().get(track.id());
            check(policy == (oldPrepared == null ? CorridorCenterlineOptimizer.OriginalCostPolicy.DIRECT_MEASURED_CORE_V2
                : CorridorCenterlineOptimizer.OriginalCostPolicy.HISTORICAL), "RAW_EFFECTIVE_POLICY:" + track.id());
            if (policy != null) retainedMetadata += 128;
            var oldWork = old.tracker().correspondenceWork().get(track.id());
            var newWork = current.tracker().correspondenceWork().get(track.id());
            check(withoutReservation(oldWork.preparation()).equals(withoutReservation(newWork.preparation())), "NATIVE_PREPARATION_CHANGED:" + track.id());
            check(withoutReservation(oldWork.hardGeometry()).equals(withoutReservation(newWork.hardGeometry())), "NATIVE_GEOMETRY_WORK_CHANGED:" + track.id());
            check(newWork.hardGeometry().metricCacheReservedBytes() == oldWork.hardGeometry().metricCacheReservedBytes() + retainedMetadata,
                "COMBINED_CACHE_METADATA_DELTA:" + track.id());
            check(newWork.hardGeometry().metricCacheReservedBytes() <= 128L * 1024 * 1024, "COMBINED_CACHE_CAP:" + track.id());
            check(oldWork.preparation().estimatedPreparedTableBytes().isPresent() == newWork.preparation().estimatedPreparedTableBytes().isPresent(),
                "PREPARED_BYTE_AVAILABILITY:" + track.id());
            if (oldWork.preparation().estimatedPreparedTableBytes().isPresent()) {
                long oldBytes = oldWork.preparation().estimatedPreparedTableBytes().orElseThrow();
                long newBytes = newWork.preparation().estimatedPreparedTableBytes().orElseThrow();
                check(newBytes == oldBytes + retainedMetadata, "COMBINED_TABLE_METADATA_DELTA:" + track.id());
                check(newBytes <= 128L * 1024 * 1024, "COMBINED_TABLE_CAP:" + track.id());
            }
            check(afterResult.costs().size() == afterResult.offsetsPx().size(), "FINAL_NATIVE_COST_ROWS:" + track.id());
            check(Math.abs(afterResult.totalCost() - afterResult.costs().stream().mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum()) <= 1e-9,
                "FINAL_NATIVE_COST_TOTAL:" + track.id());
            guards.append("reservation ").append(track.id()).append(" cumulativePolicyMetadataBytes=").append(retainedMetadata)
                .append(" R4=").append(oldWork).append(" R7=").append(newWork).append('\n');
            if (oldPrepared != null) {
                check(beforeResult.equals(afterResult), "ADMITTED_NATIVE_OUTPUT_CHANGED:" + track.id());
                check(staticWorkCompatible(workVector(beforeResult), workVector(afterResult), true), "ADMITTED_WORK_CHANGED:" + track.id());
            }
            var oldSupport = CorridorEngineAdapter.support(track, beforeResult.offsetsPx(), before, f.evidence(), "native", true, oldPrepared == null ? null : oldPrepared.schedule());
            var newSupport = CorridorEngineAdapter.support(track, afterResult.offsetsPx(), after, f.evidence(), "native", true, newPrepared == null ? null : newPrepared.schedule());
            check(oldSupport.stream().map(CorridorR7PublicProductionRegressionTest::rawRole).toList().equals(newSupport.stream().map(CorridorR7PublicProductionRegressionTest::rawRole).toList()), "RAW_OBSERVATION_OWNERSHIP_CHANGED:" + track.id());
            check(before.get(0).equals(after.get(0)) && before.get(before.size() - 1).equals(after.get(after.size() - 1)), "FIXED_ENDPOINT_CHANGED:" + track.id());
            var oldAuthority = authorityFailures(before, authority); var newAuthority = authorityFailures(after, authority);
            var oldContacts = contacts(before); var newContacts = contacts(after);
            var oldFoldbacks = FinalGeometryEvaluator.backtrackFindings(before); var newFoldbacks = FinalGeometryEvaluator.backtrackFindings(after);
            check(oldAuthority.containsAll(newAuthority), "NEW_SOURCE_AUTHORITY_FAILURE:" + track.id());
            check(oldContacts.containsAll(newContacts), "NEW_TOPOLOGY_CONTACT:" + track.id());
            check(new HashSet<>(oldFoldbacks).containsAll(newFoldbacks), "NEW_FOLDBACK:" + track.id());
            guards.append(track.id()).append(" parent=").append(track.parent()).append(" children=").append(track.childTrackIds())
                .append("\nR4ownership=").append(oldSupport).append("\nR7ownership=").append(newSupport)
                .append("\nauthority=").append(oldAuthority).append(" -> ").append(newAuthority)
                .append("\ncontacts=").append(oldContacts).append(" -> ").append(newContacts)
                .append("\nfoldbacks=").append(oldFoldbacks).append(" -> ").append(newFoldbacks).append('\n');
            for (int i = 1; i + 1 < Math.min(before.size(), after.size()); i++) triples.append(track.id()).append('\t').append(i).append('\t')
                .append(oldPrepared == null ? i : oldPrepared.schedule().originalRow(i)).append('\t').append(metricPeak(before, i, i)).append('\t')
                .append(metricPeak(after, i, i)).append('\t').append(physicalRms(before, i, i)).append('\t').append(physicalRms(after, i, i)).append('\n');
        }
        check(retainedMetadata == current.tracker().executedCostPolicyMetadataBytes(), "TOTAL_DECLARED_POLICY_METADATA");
        write("whole-route-triples.tsv", triples); write("whole-route-guards.txt", guards);
    }

    /** Exact old fresh-at-query predicate, on original59..65 instead of refit controls116..128. */
    private int denseSameFraction(List<MetricPoint> before, List<MetricPoint> after, ImageCostField image) throws Exception {
        int bad = 0; double rawUpper = 0; for (int i = 59; i <= 65; i++) rawUpper = Math.max(rawUpper, before.get(i).yMeters() - upper(before.get(i).xMeters()));
        var rows = new StringBuilder("segment\tfraction\tx\ty\tanalyticError\trawError\trawCoreDistance\tfreshCoreDistance\tsupport\tsigma\timageEnergy\tpass\n");
        for (int i = 60; i <= 65; i++) {
            var a = after.get(i - 1); var b = after.get(i); var direction = subtract(b, a); int count = Math.max(1, (int) Math.ceil(a.distanceTo(b) / .25));
            for (int j = 0; j <= count; j++) {
                double fraction = (double) j / count; var q = lerp(a, b, fraction); var rawQ = lerp(before.get(i - 1), before.get(i), fraction);
                var fresh = image.freezeProfile(q, direction); var scalar = image.sampleRoute(q, direction);
                var rawProfile = image.freezeProfile(rawQ, subtract(before.get(i), before.get(i - 1)));
                double error = Math.abs(q.yMeters() - upper(q.xMeters())), rawError = Math.abs(rawQ.yMeters() - upper(rawQ.xMeters()));
                double freshDistance = Math.max(0, Math.max(fresh.coreMinimumMeters(), -fresh.coreMaximumMeters()));
                double rawDistance = Math.max(0, Math.max(rawProfile.coreMinimumMeters(), -rawProfile.coreMaximumMeters()));
                double sigma = fresh.localizationSigmaMeters();
                boolean okay = fresh.support() == ImageCostField.FrozenSupport.MEASURED && freshDistance <= Math.max(rawDistance, sigma) + 1e-9
                    && scalar.isPresent() && Double.isFinite(scalar.get().imageEnergy()) && error <= Math.max(rawError, sigma) + 1e-9;
                if (!okay) bad++;
                check(Math.abs(q.xMeters() - rawQ.xMeters()) <= 1e-8, "DENSE_SAME_PHYSICAL_X_UNVERIFIED:" + i + ":" + j);
                check(q.yMeters() - upper(q.xMeters()) >= -2.5 && q.yMeters() - upper(q.xMeters()) <= rawUpper + .5, "TARGET_INTERVAL_UPPER_BRANCH_BOUND:" + i + ":" + j);
                rows.append(i - 1).append('\t').append(fraction).append('\t').append(q.xMeters()).append('\t').append(q.yMeters()).append('\t')
                    .append(error).append('\t').append(rawError).append('\t').append(rawDistance).append('\t').append(freshDistance).append('\t')
                    .append(fresh.support()).append('\t').append(sigma).append('\t').append(scalar.map(ImageCostField.RouteSample::imageEnergy).orElse(Double.NaN)).append('\t').append(okay).append('\n');
            }
        }
        write("dense-fresh-same-fraction.tsv", rows); return bad;
    }

    /** Fixed synthetic-truth controls; shape signs do not assert every real subpixel bend is resolvable. */
    private record ShapeControl(String label, String actualTrackId, int firstRow, double analyticShift,
            int direction, List<MetricPoint> directCenters, List<MetricPoint> analyticTruth) { }

    private static List<ShapeControl> preassertShapeControls(CorridorDirectPositionCostPolicyTest.Owned old,
            EvidenceSnapshot evidence) {
        return List.of(sourceControl("decreasing-lower-25-27", old, evidence, 25, -10., -1),
            sourceControl("increasing-middle-79-81", old, evidence, 79, -5., 1),
            sourceControl("genuine-lower-apex-60-62", old, evidence, 60, -10., 0));
    }

    private static ShapeControl sourceControl(String label, CorridorDirectPositionCostPolicyTest.Owned old,
            EvidenceSnapshot evidence, int first, double shift, int direction) {
        var tracked = old.tracked(); int middle = first + 1;
        var anchor = tracked.profiles().get(middle).source().anchorScreen();
        double x = evidence.transform().pixelCenterToMetric(anchor.x, anchor.y).xMeters();
        double expected = upper(x) + shift;
        var track = tracked.tracks().stream().filter(t -> !t.parent() && t.points().containsKey(middle))
            .min(Comparator.comparingDouble(t -> Math.abs(metricY(tracked.profiles().get(middle),
                t.points().get(middle).band().centerOffsetPx(), evidence) - expected))).orElseThrow();
        assertTrue(Math.abs(metricY(tracked.profiles().get(middle), track.points().get(middle).band().centerOffsetPx(), evidence) - expected) <= .5,
            label + " must identify the geometric analytic strand within factual uncertainty");
        assertFalse(old.tracker().correspondencePreparations().get(track.id()).admitted(), label + " requires original-domain control");
        assertEquals(101, old.tracked().optimizations().get(track.id()).offsetsPx().size());
        var centers = new ArrayList<MetricPoint>(); var truth = new ArrayList<MetricPoint>();
        for (int row = first; row <= first + 2; row++) {
            var point = track.points().get(row); assertNotNull(point);
            assertEquals(CorridorPointSupport.DIRECT_UNION, point.support());
            assertEquals(CorridorBand.BoundaryCompleteness.COMPLETE, point.band().boundaryCompleteness());
            assertEquals(.5, point.band().uncertaintyPx(), label + " existing factual half-source-pixel uncertainty");
            var profile = tracked.profiles().get(row).source(); var p = profile.anchorScreen(); var normal = profile.normalScreen();
            var center = evidence.transform().pixelCenterToMetric(p.x + normal.x * point.band().centerOffsetPx(),
                p.y + normal.y * point.band().centerOffsetPx());
            centers.add(center); truth.add(new MetricPoint(center.xMeters(), upper(center.xMeters()) + shift));
        }
        if (direction != 0) for (int i = 1; i < 3; i++) {
            assertTrue(direction * (centers.get(i).yMeters() - centers.get(i - 1).yMeters()) > 0, label + " direct centers must be strictly monotonic");
            assertTrue(direction * (truth.get(i).yMeters() - truth.get(i - 1).yMeters()) > 0, label + " analytic truth must be strictly monotonic");
        } else {
            assertTrue(centers.get(1).yMeters() > centers.get(0).yMeters() && centers.get(1).yMeters() > centers.get(2).yMeters(), "Measured source has a real apex61");
            assertTrue(truth.get(1).yMeters() > truth.get(0).yMeters() && truth.get(1).yMeters() > truth.get(2).yMeters(), "Independent synthetic truth has a real apex61");
            assertTrue(signedTurn(centers, 1) < 0 && signedTurn(truth, 1) < 0, "Both source turns are negative");
        }
        return new ShapeControl(label, track.id(), first, shift, direction, List.copyOf(centers), List.copyOf(truth));
    }

    private void shapeGates(List<ShapeControl> controls, CorridorDirectPositionCostPolicyTest.Owned old,
            CorridorDirectPositionCostPolicyTest.Owned current, EvidenceSnapshot evidence, ImageCostField image) throws Exception {
        var rows = new StringBuilder("control\tactualTrack\toriginalRow\tx\tR4y\tR7y\tmeasuredY\tanalyticY\tR4error\tR7error\n");
        var summary = new StringBuilder();
        for (var control : controls) {
            var oldResult = old.tracked().optimizations().get(control.actualTrackId());
            var newResult = current.tracked().optimizations().get(control.actualTrackId());
            if (newResult == null || newResult.offsetsPx().size() != 101) { check(false, "SHAPE_CONTROL_NOT_ORIGINAL101:" + control.label()); continue; }
            check(current.tracker().executedCostPolicies().get(control.actualTrackId()) == CorridorCenterlineOptimizer.OriginalCostPolicy.DIRECT_MEASURED_CORE_V2,
                "SHAPE_CONTROL_EFFECTIVE_POLICY_NOT_V2:" + control.label());
            var before = points(oldResult, evidence); var after = points(newResult, evidence); int first = control.firstRow();
            for (int row = first; row <= first + 2; row++) {
                var q = after.get(row); var raw = before.get(row); double analytic = upper(q.xMeters()) + control.analyticShift();
                double error = Math.abs(q.yMeters() - analytic), rawError = Math.abs(raw.yMeters() - (upper(raw.xMeters()) + control.analyticShift()));
                rows.append(control.label()).append('\t').append(control.actualTrackId()).append('\t').append(row).append('\t').append(q.xMeters())
                    .append('\t').append(raw.yMeters()).append('\t').append(q.yMeters()).append('\t').append(control.directCenters().get(row - first).yMeters())
                    .append('\t').append(analytic).append('\t').append(rawError).append('\t').append(error).append('\n');
                if (control.direction() == 0) {
                    check(error <= .5 + 1e-9, "CURVE_ABSOLUTE_FACTUAL_SIGMA:" + row);
                    var fresh = image.freezeProfile(q, tangent(after, row)); var scalar = image.sampleRoute(q, tangent(after, row));
                    var oldFresh = image.freezeProfile(raw, tangent(before, row));
                    double distance = Math.max(0, Math.max(fresh.coreMinimumMeters(), -fresh.coreMaximumMeters()));
                    double oldDistance = Math.max(0, Math.max(oldFresh.coreMinimumMeters(), -oldFresh.coreMaximumMeters()));
                    double sigma = fresh.localizationSigmaMeters();
                    boolean fit = fresh.support() == ImageCostField.FrozenSupport.MEASURED && distance <= Math.max(oldDistance, sigma) + 1e-9
                        && scalar.isPresent() && Double.isFinite(scalar.get().imageEnergy()) && error <= Math.max(rawError, sigma) + 1e-9;
                    check(fit, "CURVE_FRESH_SOURCE_FIT:" + row);
                    summary.append("CURVE_FIT row=").append(row).append(" support=").append(fresh.support()).append(" distance=").append(distance)
                        .append(" rawDistance=").append(oldDistance).append(" sigma=").append(sigma).append(" sample=").append(scalar).append(" pass=").append(fit).append('\n');
                }
            }
            double firstStep = after.get(first + 1).yMeters() - after.get(first).yMeters();
            double secondStep = after.get(first + 2).yMeters() - after.get(first + 1).yMeters();
            double turn = signedTurn(after, first + 1);
            if (control.direction() != 0) {
                check(control.direction() * firstStep >= -1e-9 && control.direction() * secondStep >= -1e-9, "MONOTONIC_SOURCE_REVERSAL:" + control.label());
            } else {
                check(firstStep > 1e-9 && secondStep < -1e-9, "CURVE_APEX61_FLATTENED_OR_REVERSED");
                check(turn < 0, "CURVE_SIGNED_TURN_REVERSED_OR_FLAT");
            }
            summary.append(control.label()).append(" actualTrack=").append(control.actualTrackId()).append(" firstStep=").append(firstStep)
                .append(" secondStep=").append(secondStep).append(" R4signedTurn=").append(signedTurn(before, first + 1))
                .append(" R7signedTurn=").append(turn).append(" measuredSignedTurn=").append(signedTurn(control.directCenters(), 1))
                .append(" analyticSignedTurn=").append(signedTurn(control.analyticTruth(), 1)).append('\n');
        }
        write("shape-controls-and-fit.tsv", rows); write("shape-controls-summary.txt", summary);
    }
    private static double signedTurn(List<MetricPoint> p, int i) {
        double incoming = Math.atan2(p.get(i).yMeters() - p.get(i - 1).yMeters(), p.get(i).xMeters() - p.get(i - 1).xMeters());
        double outgoing = Math.atan2(p.get(i + 1).yMeters() - p.get(i).yMeters(), p.get(i + 1).xMeters() - p.get(i).xMeters());
        return Math.toDegrees(Math.atan2(Math.sin(outgoing - incoming), Math.cos(outgoing - incoming)));
    }

    private Set<String> authorityFailures(List<MetricPoint> points, CorridorWorkingSchedule.SegmentAuthority authority) {
        var result = new LinkedHashSet<String>();
        for (int i = 0; i < points.size(); i++) {
            diagnosticAuthorityCalls++; if (!authority.admits(points.get(i), points.get(i))) result.add("point:" + i);
            if (i > 0) { diagnosticAuthorityCalls++; if (!authority.admits(points.get(i - 1), points.get(i))) result.add("edge:" + i); }
        }
        return result;
    }
    private static Set<String> contacts(List<MetricPoint> points) {
        var result = new LinkedHashSet<String>();
        for (int i = 0; i + 1 < points.size(); i++) for (int j = i + 2; j + 1 < points.size(); j++) {
            var a = points.get(i); var b = points.get(i + 1); var c = points.get(j); var d = points.get(j + 1);
            if (Line2D.linesIntersect(a.xMeters(), a.yMeters(), b.xMeters(), b.yMeters(), c.xMeters(), c.yMeters(), d.xMeters(), d.yMeters())) result.add(i + ":" + j);
        }
        return result;
    }
    private static List<MetricPoint> expectedNative() throws Exception {
        var points = new ArrayList<MetricPoint>(); var matcher = Pattern.compile("xMeters=([^,]+), yMeters=([^\\]]+)").matcher(new String(Objects.requireNonNull(CorridorR7PublicProductionRegressionTest.class.getResourceAsStream("/corridor/g006-public-r4-native101.txt")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        while (matcher.find()) points.add(new MetricPoint(Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))));
        assertEquals(101, points.size()); return List.copyOf(points);
    }
    private static List<MetricPoint> points(CorridorCenterlineOptimizer.OptimizationResult result, EvidenceSnapshot evidence) { return result.screenPoints().stream().map(p -> evidence.transform().pixelCenterToMetric(p.x, p.y)).toList(); }
    private static double metricY(CorridorProfile p, double offset, EvidenceSnapshot evidence) { var a = p.source().anchorScreen(); var n = p.source().normalScreen(); return evidence.transform().pixelCenterToMetric(a.x + offset * n.x, a.y + offset * n.y).yMeters(); }
    private static String rawRole(ObservationOwnership value) { return switch (value) { case DIRECT_TWO_SIDED, DIRECT_AMBIGUOUS -> "DIRECT"; default -> value.name(); }; }
    private void check(boolean pass, String reason) { if (!pass) failures.add(reason); }
    private void write(String name, Object value) throws Exception { Files.writeString(output.resolve(name), value + "\n", StandardOpenOption.CREATE_NEW); }
    private void writeDiagnostic(String name, Object value) throws Exception { Files.writeString(output.resolve(name), value + "\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
    private static MetricPoint subtract(MetricPoint b, MetricPoint a) { return new MetricPoint(b.xMeters() - a.xMeters(), b.yMeters() - a.yMeters()); }
    private static MetricPoint tangent(List<MetricPoint> p, int i) { return subtract(p.get(i == p.size() - 1 ? i : i + 1), p.get(i == 0 ? 0 : i - 1)); }
    private static MetricPoint lerp(MetricPoint a, MetricPoint b, double t) { return new MetricPoint(a.xMeters() + (b.xMeters() - a.xMeters()) * t, a.yMeters() + (b.yMeters() - a.yMeters()) * t); }
    private static double upper(double x) { double row = (x - 20) / 2; return 45 + 3.5 * Math.sin(row * Math.PI / 18) + .65 * Math.sin(row * Math.PI / 2); }
    private static double physicalRms(List<MetricPoint> p, int lo, int hi) { double sum = 0; for (int i = lo; i <= hi; i++) { double x = p.get(i - 1).xMeters() - 2 * p.get(i).xMeters() + p.get(i + 1).xMeters(), y = p.get(i - 1).yMeters() - 2 * p.get(i).yMeters() + p.get(i + 1).yMeters(); sum += x * x + y * y; } return Math.sqrt(sum / (hi - lo + 1)); }
    private static double metricPeak(List<MetricPoint> p, int lo, int hi) { double max = 0; for (int i = lo; i <= hi; i++) { double a = Math.atan2(p.get(i).yMeters() - p.get(i - 1).yMeters(), p.get(i).xMeters() - p.get(i - 1).xMeters()), b = Math.atan2(p.get(i + 1).yMeters() - p.get(i).yMeters(), p.get(i + 1).xMeters() - p.get(i).xMeters()); max = Math.max(max, Math.abs(Math.toDegrees(Math.atan2(Math.sin(b - a), Math.cos(b - a))))); } return max; }
    private static double offsetRms(List<Double> p, int lo, int hi) { double sum = 0; for (int i = lo; i <= hi; i++) { double d = p.get(i - 1) - 2 * p.get(i) + p.get(i + 1); sum += d * d; } return Math.sqrt(sum / (hi - lo + 1)); }
    private static double offsetPeak(List<Double> p, int lo, int hi) { double max = 0; for (int i = lo; i <= hi; i++) max = Math.max(max, Math.abs(Math.toDegrees(Math.atan2(p.get(i + 1) - p.get(i), 2) - Math.atan2(p.get(i) - p.get(i - 1), 2)))); return max; }
}
