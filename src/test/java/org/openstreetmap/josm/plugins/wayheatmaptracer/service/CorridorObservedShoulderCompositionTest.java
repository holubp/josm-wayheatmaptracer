package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Public scalar field regression for the actual bound R5 domain and production objective. */
class CorridorObservedShoulderCompositionTest {
    @Test
    void productionStraightObservedShoulderCorrectionPassesFixedGate() throws Exception {
        int reproduced = 0;
        for (boolean shallow : List.of(false)) {
            var fixture = field(shallow);
            NativeRun nativeRun = runOwned(fixture);
            var tracked = nativeRun.tracked();
            System.out.println("COMPOSITION BASELINE shallow=" + shallow + " status=" + nativeRun.run().result().status()
                + " certificate=" + nativeRun.certificate().status() + " usage=" + nativeRun.run().usage()
                + " totals=" + nativeRun.run().corridorReceipt().orElseThrow().totals()
                + " sampling=" + nativeRun.run().corridorReceipt().orElseThrow().sampling());
            assertTrue(nativeRun.certificate().available(), "Actual source certificate is required");
            assertNotNull(tracked, "Actual R4 tracking must complete");
            assertEquals(101, tracked.profiles().size());
            int row = 62;
            double physicalChainage = fixture.request().profileChainage().cumulativeGroundMeters().get(row);
            assertEquals(physicalChainage, tracked.profiles().get(row).source().cumulativeGroundDistanceMeters());
            // Analytic upper strand at the original x=144 physical event, never a generated track ID.
            double expectedY = 40 + latent(62) + 5;
            var target = tracked.tracks().stream().filter(t -> !t.parent() && t.points().containsKey(row))
                .min(Comparator.comparingDouble(t -> Math.abs(metricY(tracked.profiles().get(row),
                    t.points().get(row).band().centerOffsetPx(), fixture.evidence()) - expectedY))).orElse(null);
            if (target == null) {
                System.out.println("COMPOSITION INCONCLUSIVE: no direct upper analytic strand at original row62");
                continue;
            }
            var baseline = tracked.optimizations().get(target.id());
            var work = nativeRun.tracker().correspondenceWork().get(target.id());
            var admission = nativeRun.tracker().correspondencePreparations().get(target.id());
            System.out.println("COMPOSITION TARGET shallow=" + shallow + " track=" + target.id() + " row=" + row
                + " chainage=" + physicalChainage + " expected-upper-y=" + expectedY
                + " band=" + target.points().get(row).band() + " tube=" + tracked.tubes().get(target.id()).at(row)
                + " admission=" + admission + " gap=" + work.preparation().gapWork());
            if (baseline == null || admission.admitted() || baseline.offsetsPx().size() != 101) {
                System.out.println("COMPOSITION INCONCLUSIVE: original full-row off-shoulder event not reproduced; admission=" + admission);
                continue;
            }
            var band = target.points().get(row).band();
            double selected = baseline.offsetsPx().get(row);
            System.out.println("COMPOSITION BASELINE TARGET chosen=" + selected + " actual-turn="
                + peakTurn(baseline.offsetsPx(), row, row) + " local-rms=" + rms(baseline.offsetsPx(), 60, 64)
                + " local-peak=" + peakTurn(baseline.offsetsPx(), 60, 64));
            if (selected >= band.shoulderMinPx() && selected <= band.shoulderMaxPx()) {
                System.out.println("COMPOSITION INCONCLUSIVE: native R4 original upper-strand row62 choice is already inside its measured shoulder");
                continue;
            }
            reproduced++;
            var counter = counterfactual(nativeRun, fixture, target.id());
            var changed = counter.results().get(target.id());
            printRows("BASELINE", baseline, row);
            printRows("COUNTERFACTUAL", changed, row);
            double oldRms = rms(baseline.offsetsPx(), 60, 64), newRms = rms(changed.offsetsPx(), 60, 64);
            double oldTurn = peakTurn(baseline.offsetsPx(), 60, 64), newTurn = peakTurn(changed.offsetsPx(), 60, 64);
            System.out.println("COMPOSITION FIXED GATE shallow=" + shallow + " row62 rms=" + oldRms + " -> " + newRms
                + " peak=" + oldTurn + " -> " + newTurn + " counter-calls=" + counter.solves()
                + " counter-pair-work=" + counter.pairs() + " counter-transitions=" + counter.transitions()
                + " observed-membership-checks=" + counter.memberships());
            assertTrue(newRms <= .60 * oldRms, "Original fixed-window 40% RMS gate: " + oldRms + " -> " + newRms);
            assertTrue(newTurn <= .75 * oldTurn, "Original fixed-window 25% peak gate: " + oldTurn + " -> " + newTurn);
            assertTrue(peakTurn(changed.offsetsPx(), 59, 65) < oldTurn, "No equally large adjacent transfer");
        }
        assertEquals(1, reproduced,
            "INCONCLUSIVE: unchanged public straight field must reproduce actual R4 off-shoulder row62");
    }

    @Test
    void productionShallowObservedShoulderPreservesSupportedTarget() throws Exception {
        var fixture = field(true);
        var nativeRun = runOwned(fixture); var tracked = nativeRun.tracked();
        assertTrue(nativeRun.certificate().available()); assertNotNull(tracked); assertEquals(101, tracked.profiles().size());
        int row = 62;
        double expectedY = 40 + latent(62) + 5;
        var target = tracked.tracks().stream().filter(t -> !t.parent() && t.points().containsKey(row))
            .min(Comparator.comparingDouble(t -> Math.abs(metricY(tracked.profiles().get(row),
                t.points().get(row).band().centerOffsetPx(), fixture.evidence()) - expectedY))).orElseThrow();
        assertTrue(Math.abs(metricY(tracked.profiles().get(row), target.points().get(row).band().centerOffsetPx(),
            fixture.evidence()) - expectedY) <= 1.0, "Must retain the original upper analytic strand");
        var baseline = tracked.optimizations().get(target.id());
        assertEquals(101, baseline.offsetsPx().size());
        var sourcePoint = target.points().get(row); var band = sourcePoint.band();
        assertEquals(CorridorBand.BoundaryCompleteness.COMPLETE, band.boundaryCompleteness());
        assertTrue(inside(baseline.offsetsPx().get(row), band));
        var sourceProvenance = Map.copyOf(target.points());
        var counter = counterfactual(nativeRun, fixture, target.id());
        var changed = counter.results().get(target.id());
        var before = metricPoints(baseline, fixture.evidence()); var after = metricPoints(changed, fixture.evidence());
        printRows("SHALLOW BASELINE", baseline, row); printRows("SHALLOW COUNTERFACTUAL", changed, row);
        assertEquals(sourceProvenance, target.points(), "Filtering must not mutate raw observation provenance");
        assertTrue(inside(changed.offsetsPx().get(row), band), "Original row62 COMPLETE shoulder remains authoritative");
        for (int i = 59; i <= 65; i++) {
            var observed = target.points().get(i);
            if (observed != null && observed.support() == CorridorPointSupport.DIRECT_UNION
                && observed.band().boundaryCompleteness() == CorridorBand.BoundaryCompleteness.COMPLETE
                && inside(baseline.offsetsPx().get(i), observed.band())) {
                assertTrue(inside(changed.offsetsPx().get(i), observed.band()), "Originally in-shoulder direct row " + i + " must remain inside");
            }
        }
        for (int endpoint : List.of(0,100)) {
            assertEquals(0.0, baseline.offsetsPx().get(endpoint)); assertEquals(0.0, changed.offsetsPx().get(endpoint));
            assertEquals(before.get(endpoint), after.get(endpoint), "Fixed source endpoints remain exact");
        }
        var oldError = latentError(before,60,64); var newError = latentError(after,60,64);
        double oldPeak = metricPeak(before,59,65), newPeak = metricPeak(after,59,65);
        double oldRms = rms(baseline.offsetsPx(),59,65), newRms = rms(changed.offsetsPx(),59,65);
        var context = (CorridorWorkingSchedule.BoundContext) member(nativeRun.tracker(),"correspondenceContext");
        var oldAuthority = authorityFailures(before,context); var newAuthority = authorityFailures(after,context);
        var oldContacts = properContacts(before); var newContacts = properContacts(after);
        var oldSupport = CorridorEngineAdapter.support(target,baseline.offsetsPx(),before,fixture.evidence(),"native",true,null);
        var newSupport = CorridorEngineAdapter.support(target,changed.offsetsPx(),after,fixture.evidence(),"native",true,null);
        for(int i=0;i<oldSupport.size();i++) assertEquals(rawRole(oldSupport.get(i)),rawRole(newSupport.get(i)), "Raw ownership row="+i);
        System.out.println("SHALLOW PRESERVATION target="+target.id()+" row62="+baseline.offsetsPx().get(62)+" -> "+changed.offsetsPx().get(62)
            +" latent-max="+oldError[0]+" -> "+newError[0]+" latent-rms="+oldError[1]+" -> "+newError[1]
            +" metric-peak="+oldPeak+" -> "+newPeak+" adjacent-second-difference="+oldRms+" -> "+newRms
            +" contacts="+oldContacts+" -> "+newContacts+" authority="+oldAuthority+" -> "+newAuthority
            +" native-usage="+nativeRun.run().usage()+" native-totals="+nativeRun.run().corridorReceipt().orElseThrow().totals()
            +" counter-solves="+counter.solves()+" counter-pair-work="+counter.pairs()+" counter-transitions="+counter.transitions()
            +" membership-checks="+counter.memberships()+" removed="+counter.removed()+" extra-authority-diagnostic-calls="+(before.size()+after.size()-2));
        assertTrue(newError[0] <= oldError[0], "Shallow maximum latent error must not increase");
        assertTrue(newError[1] <= oldError[1], "Shallow RMS latent error must not increase");
        assertTrue(newPeak <= oldPeak, "Shallow adjacent metric peak turn must not increase");
        assertTrue(newRms <= oldRms, "Shallow adjacent second-difference RMS must not increase");
        assertTrue(oldContacts.containsAll(newContacts), "No new proper self-contact");
        assertTrue(oldAuthority.containsAll(newAuthority), "No new bound-source authority failure");
        if(counter.removed()==0) {
            assertEquals(baseline.screenPoints(),changed.screenPoints()); assertEquals(baseline.offsetsPx(),changed.offsetsPx());
            assertEquals(baseline.totalCost(),changed.totalCost(),"No changed domain means exact ordered geometry/objective");
        }
    }

    private Counter counterfactual(NativeRun baseline, CorridorPolicyFixture.Fixture fixture, String target) throws Exception {
        var f = withPolicy(fixture, CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1);
        var nativeRun = runOwned(f); var tracked = nativeRun.tracked();
        assertNotNull(tracked); assertTrue(nativeRun.certificate().available());
        var receipt = nativeRun.run().corridorReceipt().orElseThrow();
        assertEquals(CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1, receipt.executionPolicy());
        assertEquals(baseline.tracked().profiles(), tracked.profiles());
        assertEquals(baseline.tracked().tracks(), tracked.tracks());
        assertEquals(baseline.tracked().tubes(), tracked.tubes());
        assertEquals(baseline.tracked().sparseBundles(), tracked.sparseBundles());
        long removed=0, checks=0;
        for(var work:receipt.tracks().values()) {
            var observed=work.preparation().observedWork().orElseThrow();
            checks+=observed.statesExamined(); removed+=observed.removedStates();
            assertTrue(observed.rows().stream().noneMatch(r->r.disposition()==CorridorObservedShoulderDomain.Disposition.REFUSED_EMPTY_DOMAIN),
                "Positive scalar regression cannot hide an empty-domain refusal");
            if(work.admissionStatus().equals("ADMITTED")) {
                assertEquals(0,observed.admissionUnits());
                assertEquals(baseline.tracked().optimizations().get(work.trackId()),tracked.optimizations().get(work.trackId()));
            }
        }
        assertEquals(checks,receipt.totals().observedAdmissionUnits().orElseThrow());
        assertEquals(receipt.totals().pairVisits(),nativeRun.run().usage().pairVisits());
        // Re-run through the ordinary immutable final/assignment pipeline, including rejected candidates.
        var pipeline=new ModernTracePipeline(new CorridorEngineAdapter("native")).runWithUsage(
            f.request(),f.evidence(),f.network(),f.options(),CancellationProbe.NONE);
        assertEquals(nativeRun.run().result(),pipeline.result().inference());
        for(var route:pipeline.result().routes()) {
            assertEquals(route.pointIds().size(),route.assignments().size());
            for(int i=0;i<route.pointIds().size();i++)
                assertEquals(route.hypothesis().points().get(i),route.assignments().get(route.pointIds().get(i)));
        }
        var frozen=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput(f.request(),f.evidence(),f.network(),f.options());
        var bundle=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory.createLive(
            "public-r5-observed",frozen,pipeline.result(),"preview","synthetic",0,null,false,false);
        bundle=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.CorridorExecutionArtifacts.attach(
            bundle,frozen,pipeline.result().inference(),pipeline.corridorReceipt().orElseThrow());
        var csv=new String(bundle.artifact("corridor-execution.csv").bytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("R5_OWNED_OBSERVED_SHOULDER_V1")); assertTrue(csv.contains("observed_shoulder_schema"));
        assertTrue(csv.contains("state_membership_checks")); assertTrue(csv.contains("removed_offsets_px"));
        return new Counter(tracked.optimizations(),receipt.totals().solverStarts(),receipt.totals().pairVisits(),
            receipt.totals().attemptedTransitions(),checks,removed);
    }

    @Test void actualR5MissingSourceCertificateKeepsOldSolveAndReportsTypedZeroWorkSkip() {
        var f=CorridorPolicyFixture.straight(true,false,Set.of()); var r=f.request();
        var way=(DetachedWay)f.network().primitives().get(r.selectedWayKey());
        var geo=way.nodeKeys().stream().map(k->((DetachedNode)f.network().primitives().get(k)).coordinate()).toList();
        var metric=geo.stream().map(f.evidence().coordinateFrame()::toMetric).toList(); double total=0;
        for(int i=1;i<metric.size();i++)total+=metric.get(i-1).distanceTo(metric.get(i));
        var rows=new ArrayList<Double>(); double end=Math.nextDown(total);
        for(int i=0;i<30;i++)rows.add(i==29?end:end*i/29);
        var chainage=new ProfileChainage(rows,total/29); TraceEngineRun baseline=null;
        for(var policy:List.of(CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1,CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1)) {
            var input=CorridorTraceInput.from(chainage,metric,f.evidence().coordinateFrame(),f.evidence().transform(),.5).withPolicy(policy,Set.of());
            var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),
                new TraceBudgets(96,15000,128000000,32,8),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),
                r.settingsHash(),r.parameterHash(),r.samplerId(),total/29,chainage,r.evidenceResolution(),Optional.of(input));
            var run=new CorridorEngineAdapter("native").traceWithUsage(request,f.evidence(),f.network(),CancellationProbe.NONE);
            var receipt=run.corridorReceipt().orElseThrow();
            assertEquals("REFUSED_SOURCE_PROTECTION_CHAINAGE_OWNERSHIP",receipt.certificateStatus());
            assertTrue(receipt.visitedTracks()>0); assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,run.result().status());
            if(baseline==null)baseline=run;
            else {
                assertEquals(baseline.result(),run.result()); assertEquals(baseline.usage(),run.usage());
                for(var work:receipt.tracks().values()) {
                    var observed=work.preparation().observedWork().orElseThrow();
                    assertEquals(CorridorObservedShoulderDomain.Disposition.SKIPPED_NO_BOUND_SOURCE_AUTHORITY,observed.outcome());
                    assertEquals(0,observed.admissionUnits()); assertEquals(0,observed.retainedBytes());
                }
            }
        }
    }

    @Test void explicitR5RejectsMoveModeBeforeSampling() {
        var f=withPolicy(CorridorPolicyFixture.straight(true,false,Set.of()),CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1);
        var r=f.request();
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),AlignmentMode.MOVE_EXISTING_NODES,r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),
            r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        assertThrows(IllegalArgumentException.class,()->new CorridorEngineAdapter("native").traceWithUsage(request,f.evidence(),f.network(),CancellationProbe.NONE));
    }

    @Test void admittedCancellationRetainsPrioritySkipAndPartialNativeWork() {
        var f=withPolicy(CorridorPolicyFixture.admittedParabola(),CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1);
        var checkpoints=new java.util.concurrent.atomic.AtomicInteger();
        CancellationProbe cancellation=()->StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
            frame.getClassName().equals(CorridorCenterlineOptimizer.class.getName()) && frame.getMethodName().equals("optimizePreparedInternal")))
            &&checkpoints.incrementAndGet()==2;
        var run=new CorridorEngineAdapter("native").traceWithUsage(f.request(),f.evidence(),f.network(),cancellation);
        assertEquals(TraceHypothesisSet.Status.CANCELLED,run.result().status());
        var receipt=run.corridorReceipt().orElseThrow();
        var admitted=receipt.tracks().values().stream().filter(w->w.admissionStatus().equals("ADMITTED")).toList();
        assertFalse(admitted.isEmpty()); assertTrue(receipt.totals().unaryEvaluations()>0);
        assertEquals(receipt.totals().unaryEvaluations(),run.result().evaluatedStates());
        for(var work:admitted) {
            assertEquals("CANCELLED",work.preparation().outcome());
            var observed=work.preparation().observedWork().orElseThrow();
            assertEquals(CorridorObservedShoulderDomain.Disposition.SKIPPED_OWNED_CORRESPONDENCE_PRIORITY,observed.outcome());
            assertEquals(0,observed.admissionUnits()); assertEquals(0,observed.retainedBytes());
        }
    }

    private static CorridorPolicyFixture.Fixture withPolicy(CorridorPolicyFixture.Fixture f,CorridorTraceInput.ExecutionPolicy policy) {
        var r=f.request();
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),
            r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),
            Optional.of(r.corridorInput().orElseThrow().withPolicy(policy,r.corridorInput().orElseThrow().additionalFixedOccurrences())));
        return new CorridorPolicyFixture.Fixture(request,f.evidence(),f.network(),f.options());
    }

    @Test void positiveAdmittedCorrespondenceKeepsExactOldSolveAndZeroObservedWork() throws Exception {
        var f=CorridorPolicyFixture.admittedParabola();
        var old=runOwned(withPolicy(f,CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1));
        var current=runOwned(withPolicy(f,CorridorTraceInput.ExecutionPolicy.R5_OWNED_OBSERVED_SHOULDER_V1));
        assertTrue(current.tracker().correspondencePreparations().values().stream().anyMatch(CorridorWorkingSchedule.Preparation::admitted));
        assertEquals(old.tracked().optimizations(),current.tracked().optimizations());
        assertEquals(old.run().result(),current.run().result());
        assertEquals(old.run().usage(),current.run().usage());
        for(var work:current.tracker().correspondenceWork().values()) if(work.admission().orElseThrow().admitted()) {
            assertEquals(CorridorObservedShoulderDomain.Disposition.SKIPPED_OWNED_CORRESPONDENCE_PRIORITY,
                work.preparation().observedWork().orElseThrow().outcome());
            assertEquals(0,work.preparation().observedWork().orElseThrow().admissionUnits());

        }
    }

    CorridorPolicyFixture.Fixture field(boolean shallow) {
        int width = 241, height = 81; var values = new double[width * height]; var valid = new boolean[values.length]; Arrays.fill(valid, true);
        for (int x = 0; x < width; x++) {
            double profile = (x - 20) / 2.0;
            for (int y = 0; y < height; y++) {
                double intensity = .03;
                for (int strand = 0; strand < 3; strand++) {
                    double center = 40 + latent(profile) + (strand - 1) * 5.0;
                    double amplitude = .07 + .02 * Math.sin(profile * .31 + strand * 1.7);
                    intensity += amplitude * Math.exp(-.5 * Math.pow((y - center) / .6, 2));
                }
                // Exact same 8-bit scalar pixel values as the archived rendered-raster field.
                values[y * width + x] = Math.round(255 * Math.min(1, intensity)) / 255.0;
            }
        }
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14), new GeographicPoint(49.99,13.99), new GeographicPoint(50.01,14.02));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var region = MetricRegion.rectangle(0,0,width-1,height-1);
        var scalar = new ScalarEvidenceField(width,height,values,valid,new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
            "native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var evidence = new EvidenceSnapshot("public-g006-composition-field",frame,transform,EvidenceResolution.nativeSource(1,1),region,
            MetricRegion.rectangle(-.5,-.5,width-.5,height-.5),Map.of("native",scalar),"unchanged-three-strand-field");
        var primitives = new LinkedHashMap<PrimitiveKey,DetachedPrimitive>(); var keys = new ArrayList<PrimitiveKey>();
        var geographic = new ArrayList<GeographicPoint>(); var factual = new ArrayList<MetricPoint>();
        for (int row = 0; row < 101; row++) {
            var point = new MetricPoint(20 + row * 2, 40 + (shallow ? .7 * Math.sin(row * Math.PI / 45.0) : 0));
            var key = PrimitiveKey.existing(PrimitiveKey.Type.NODE,row+1); var geo = frame.toGeographic(point);
            keys.add(key); geographic.add(geo); factual.add(frame.toMetric(geo));
            primitives.put(key,new DetachedNode(key,geo,Map.of(),false,false));
        }
        var way = PrimitiveKey.existing(PrimitiveKey.Type.WAY,1000); var range = new OccurrenceRange(0,100);
        primitives.put(way,new DetachedWay(way,keys,Map.of("highway","path"),false,false));
        var protectedNodes = Set.of(keys.get(0),keys.get(100)); var movable = new LinkedHashSet<>(keys); movable.removeAll(protectedNodes);
        var editable = new LinkedHashSet<>(movable); editable.add(way);
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,"g006-public-composition-v1",primitives.keySet(),
            editable,movable,protectedNodes,Set.of(),Map.of(way,List.of(range)),List.of(),region,region,true,true,true,true);
        var network = new NetworkSnapshot("public-g006-network-"+shallow,SnapshotRole.CAPTURED_BEFORE,"public-g006-dataset",1,closure,primitives,
            V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        var chainage = ProfileChainage.measured(factual,2.0);
        var input = IntervalTraceRequestFactory.corridorForAnchors(chainage,factual,geographic,evidence)
            .withPolicy(CorridorTraceInput.ExecutionPolicy.R4_OWNED_GAP_DOMAIN_V1,Set.of());
        var request = new TraceRequest(way,range,TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE,RecoveryPermissions.disabled(16),
            TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),
            "1".repeat(64),"2".repeat(64),"public-g006-same-field-r4",2.0,chainage,evidence.resolution(),Optional.of(input));
        return new CorridorPolicyFixture.Fixture(request,evidence,network,new ModernTracePipeline.Options("native",GeometryCleanupConfig.disabled(),"synthetic",0));
    }

    private NativeRun runOwned(CorridorPolicyFixture.Fixture f) throws Exception {
        var adapter = new CorridorEngineAdapter("native");
        var type = Class.forName(CorridorEngineAdapter.class.getName()+"$OwnedInvocation");
        var ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        var owned = ctor.newInstance(adapter,f.request(),f.evidence(),f.network(),CancellationProbe.NONE);
        var execute = type.getDeclaredMethod("execute"); execute.setAccessible(true);
        var run = (TraceEngineRun) execute.invoke(owned);
        return new NativeRun(run,(CorridorAwareTracker)member(owned,"tracker"),
            (CorridorAwareTracker.TrackingResult)member(owned,"tracked"),(CorridorSourceProtection.Certificate)member(owned,"certificate"));
    }
    private static Object member(Object object,String name) throws Exception { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    private static TraceBudgets debit(TraceBudgets b,long units) { assertTrue(units < b.maximumPairVisits());return new TraceBudgets(b.maximumStatesPerProfile(),b.maximumPairVisits()-units,b.maximumTransitions(),b.maximumRawAlternatives(),b.maximumDistinctAlternatives()); }
    private static double latent(double profile) { return 3.5*Math.sin(profile*Math.PI/18.0)+.65*Math.sin(profile*Math.PI/2.0); }
    private static boolean finite(CorridorBand b) { return Double.isFinite(b.centerOffsetPx())&&Double.isFinite(b.coreMinPx())&&Double.isFinite(b.coreMaxPx())&&Double.isFinite(b.shoulderMinPx())&&Double.isFinite(b.shoulderMaxPx()); }
    private static boolean sameIntervals(CorridorBand b,CorridorTubeSlice s) {return Double.compare(b.coreMinPx(),s.coreMinPx())==0&&Double.compare(b.coreMaxPx(),s.coreMaxPx())==0&&Double.compare(b.shoulderMinPx(),s.shoulderMinPx())==0&&Double.compare(b.shoulderMaxPx(),s.shoulderMaxPx())==0;}
    private static double metricY(CorridorProfile p,double offset,EvidenceSnapshot e) {var a=p.source().anchorScreen();var n=p.source().normalScreen();return e.transform().pixelCenterToMetric(a.x+offset*n.x,a.y+offset*n.y).yMeters();}
    private static boolean inside(double offset,CorridorBand b) {return offset>=b.shoulderMinPx()&&offset<=b.shoulderMaxPx();}
    private static List<MetricPoint> metricPoints(CorridorCenterlineOptimizer.OptimizationResult r,EvidenceSnapshot e) {return r.screenPoints().stream().map(p->e.transform().pixelCenterToMetric(p.x,p.y)).toList();}
    private static double[] latentError(List<MetricPoint> points,int lo,int hi) {double max=0,sum=0;for(int i=lo;i<=hi;i++){var p=points.get(i);double error=Math.abs(p.yMeters()-(40+latent((p.xMeters()-20)/2)+5));max=Math.max(max,error);sum+=error*error;}return new double[]{max,Math.sqrt(sum/(hi-lo+1))};}
    private static double metricPeak(List<MetricPoint> p,int lo,int hi) {double max=0;for(int i=lo;i<=hi;i++){double a=Math.atan2(p.get(i).yMeters()-p.get(i-1).yMeters(),p.get(i).xMeters()-p.get(i-1).xMeters());double b=Math.atan2(p.get(i+1).yMeters()-p.get(i).yMeters(),p.get(i+1).xMeters()-p.get(i).xMeters());max=Math.max(max,Math.abs(Math.toDegrees(Math.atan2(Math.sin(b-a),Math.cos(b-a)))));}return max;}
    private static Set<String> properContacts(List<MetricPoint> p) {var result=new LinkedHashSet<String>();for(int i=0;i+1<p.size();i++)for(int j=i+2;j+1<p.size();j++)if(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.properIntersection(p.get(i),p.get(i+1),p.get(j),p.get(j+1)))result.add(i+":"+j);return result;}
    private static Set<Integer> authorityFailures(List<MetricPoint> p,CorridorWorkingSchedule.BoundContext c) {var result=new LinkedHashSet<Integer>();for(int i=1;i<p.size();i++)if(!c.sourceAuthority().admits(p.get(i-1),p.get(i)))result.add(i);return result;}
    private static String rawRole(ObservationOwnership s) {return switch(s){case DIRECT_TWO_SIDED,DIRECT_AMBIGUOUS->"DIRECT";default->s.name();};}
    private static double rms(List<Double> offsets,int lo,int hi) {double sum=0;for(int i=lo;i<=hi;i++){double d=offsets.get(i-1)-2*offsets.get(i)+offsets.get(i+1);sum+=d*d;}return Math.sqrt(sum/(hi-lo+1));}
    private static double peakTurn(List<Double> offsets,int lo,int hi) {double max=0;for(int i=lo;i<=hi;i++)max=Math.max(max,Math.abs(Math.toDegrees(Math.atan2(offsets.get(i+1)-offsets.get(i),2)-Math.atan2(offsets.get(i)-offsets.get(i-1),2))));return max;}
    private static void printRows(String label,CorridorCenterlineOptimizer.OptimizationResult result,int row) {System.out.println("COMPOSITION "+label+" objective="+result.totalCost()+" unary="+result.profileCostEvaluations()+" transitions="+result.transitionEvaluations());for(int i=row-3;i<=row+3;i++)System.out.println("COMPOSITION "+label+" "+result.costs().get(i));}
    private record NativeRun(TraceEngineRun run,CorridorAwareTracker tracker,CorridorAwareTracker.TrackingResult tracked,CorridorSourceProtection.Certificate certificate) { }
    private record Filter(CorridorCenterlineOptimizer.OriginalDomain domain,long checks,long removed) { }
    private record Counter(Map<String,CorridorCenterlineOptimizer.OptimizationResult> results,long solves,long pairs,long transitions,long memberships,long removed) { }
}
