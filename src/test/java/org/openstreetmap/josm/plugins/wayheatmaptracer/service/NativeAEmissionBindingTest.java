package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeAEmissionBindingTest {
    @Test void finalPublicationPrepaysBothReceiptScansBeforeAnyEarlierRowCanBeInvalidatedByALaterCharge() {
        var armed=new boolean[1];var calls=new int[1];var target=new int[]{18};
        var normalRef=new java.util.concurrent.atomic.AtomicReference<java.awt.geom.Point2D.Double>();
        CancellationProbe probe=()->{
            if(armed[0]&&++calls[0]==target[0])normalRef.get().x+=.125;
            return false;
        };
        try(var f=new NativeAOriginalR4Fixture(probe);var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            var original=f.source.profiles().get(1).normalScreen();double nx=original.x;normalRef.set(original);armed[0]=true;
            // The existing row-by-row gate is intentionally unchanged; a later charge can
            // invalidate an already checked earlier row. It cannot serve as the final gate.
            assertDoesNotThrow(()->binding.requireCurrent(f.input));assertEquals(18,calls[0]);assertNotEquals(nx,original.x);
            original.x=nx;calls[0]=0;target[0]=1;
            long work=f.input.workBudget().workUsed();
            assertThrows(IllegalStateException.class,()->binding.requireCurrentForFinalPublication(f.input));
            assertEquals(1,calls[0]);assertEquals(3,f.input.profiles().levels().size());
            assertEquals(19*3+16*17,f.input.workBudget().workUsed()-work);
            original.x=nx;calls[0]=0;
            var retained=f.input.profiles().levelZeroProfiles().get(0).normalScreen();double retainedX=retained.x;normalRef.set(retained);
            assertThrows(IllegalStateException.class,()->binding.requireCurrentForFinalPublication(f.input));
            assertEquals(1,calls[0]);retained.x=retainedX;armed[0]=false;
            binding.requireCurrentForFinalPublication(f.input);
        }
    }
    @Test void finalPublicationGateRefusesForeignClosedAndStickyTerminalInputsWithoutIssuingAuthority() {
        try(var f=new SmallFrame();var foreign=new SmallFrame();var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            var before=f.input.workBudget().snapshot();
            assertThrows(IllegalArgumentException.class,()->binding.requireCurrentForFinalPublication(foreign.input));
            assertEquals(before,f.input.workBudget().snapshot());
            var failure=new java.util.concurrent.CancellationException("synthetic terminal");f.input.recordTerminalExecutionFailure(failure);
            assertSame(failure,assertThrows(java.util.concurrent.CancellationException.class,()->binding.requireCurrentForFinalPublication(f.input)));
            assertEquals(before,f.input.workBudget().snapshot());
            binding.close();assertThrows(IllegalArgumentException.class,()->binding.requireCurrentForFinalPublication(f.input));
            assertEquals(before,f.input.workBudget().snapshot());
        }
    }
    @Test void sourceOnlyFreshnessDoesNotAuthorizeChangedOriginalNativeGeometryOrMutableRows() {
        try(var f=new NativeAOriginalR4Fixture();var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            var original=f.source.profiles().get(1);
            var anchor=original.anchorScreen();double x=anchor.x;anchor.x+=.125;
            assertDoesNotThrow(f.input::requireRetainedSourceUnchanged);
            assertEquals(x,original.anchorScreen().x,0,"Mutable screen points are copies of the immutable native anchor");
            assertDoesNotThrow(()->binding.requireCurrent(f.input));
            var normal=original.normalScreen();double nx=normal.x;normal.x+=.125;
            assertDoesNotThrow(f.input::requireRetainedSourceUnchanged);
            assertThrows(IllegalStateException.class,()->binding.requireCurrent(f.input));normal.x=nx;
            binding.requireCurrent(f.input);
            assertThrows(UnsupportedOperationException.class,()->binding.profiles().levelZeroProfiles().set(0,f.source.profiles().get(2)));
            assertThrows(UnsupportedOperationException.class,()->original.intensitySamples().set(0,original.intensitySamples().get(1)));
            assertThrows(UnsupportedOperationException.class,()->original.peaks().clear());
            assertThrows(UnsupportedOperationException.class,()->binding.originalOrdinalByContextRow().put(1,7));
        }
    }

    @Test void geometryMetadataLimitIsStickyAndReleasesThePartialBindingOwner() {
        try(var f=new SmallFrame()) {
            try(var pressure=f.root.reserve(f.ledger.limitBytes()-f.ledger.currentBytes()-512)) {
                long baseline=f.ledger.currentBytes();
                var failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->NativeAEmissionBinding.fromOriginalGeometry(f.input));
                assertEquals(baseline,f.ledger.currentBytes());
                assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,f.input::requireRetainedSourceUnchanged));
                assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,f.input::requireOpenAndUnchanged));
            }
        }
    }
    @Test void nativeGeometryValidationDoesNotInheritLegacySemanticScansButKeepsStickyTerminals() {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        CancellationProbe legacyOnly=()->armed.get()&&StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
            frame.getClassName().equals(LegacyEmissionBinding.class.getName())&&frame.getMethodName().equals("requireNativeProfilesUnchanged")));
        try(var f=new NativeAOriginalR4Fixture(legacyOnly);var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            armed.set(true);
            assertDoesNotThrow(()->binding.requireCurrent(f.input),"A authority uses its own original geometry; Legacy semantics are incidental");
            var failure=assertThrows(java.util.concurrent.CancellationException.class,f.input::requireOpenAndUnchanged);
            assertSame(failure,assertThrows(java.util.concurrent.CancellationException.class,()->binding.requireCurrent(f.input)));
        }
    }
    @Test void nonidentityBindingPreservesOriginalNativeStateOrderAndContinuityArithmetic() {
        try(var f=new SmallFrame(true);var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            var mandatory=new JunctionContext(List.of(new EndpointConstraint(1,1,false,false,5,0,0)));
            var reference=new CorridorAwareTracker().trackDetailed(binding.profiles().levelZeroProfiles(),1,mandatory);
            try(var results=new CorridorAwareTracker().trackOptionalEmission(f.input,binding,mandatory,List.of(),GeometryCleanupConfig.disabled(),
                    NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
                for(var result:results.results()) {
                    int index=results.results().indexOf(result);String id=results.trackIds().get(index);
                    var track=reference.tracks().stream().filter(t->t.id().equals(id)).findFirst().orElseThrow();
                    var domain=new CorridorCenterlineOptimizer().nativeStateDomain(track,reference.profiles(),1,mandatory,reference.scaleEvidence(),
                        reference.tubes().get(id),reference.optimizations().get(id).endpointApproaches());
                    var expected=new ArrayList<NativeAEmissionSolver.NativeStateInventory>();
                    for(var row:domain.rows())for(var state:row.states())expected.add(new NativeAEmissionSolver.NativeStateInventory(
                        row.rowIndex(),state.stateId(),state.offsetPx(),NativeConnectorEvaluator.SourceClass.OBSERVED));
                    assertEquals(expected,result.nativeStates());assertTrue(result.consumedRows().isEmpty());assertTrue(result.c()>0);
                    double c=0;
                    for(int row=1;row<3;row++) {
                        double interval=Math.max(1,reference.profiles().get(row-1).source().anchorScreen()
                            .distance(reference.profiles().get(row).source().anchorScreen()));
                        c+=domain.rows().get(row).continuityWeight()*Math.pow((result.emitted().get(row).offsetPx()
                            -result.emitted().get(row-1).offsetPx())/interval,2);
                    }
                    assertEquals(c,result.c(),0);
                    var emitted=result.emitted();var tube=reference.tubes().get(id);
                    var referencePoints=new ArrayList<RasterPoint>();
                    for(int row=0;row<3;row++)referencePoints.add(binding.nativePoint(row,tube.at(row).centerOffsetPx()));
                    double turn=turn(emitted.get(0).raster(),emitted.get(1).raster(),emitted.get(2).raster());
                    double referenceTurn=turn(referencePoints.get(0),referencePoints.get(1),referencePoints.get(2));
                    double residual=Math.atan2(Math.sin(turn-referenceTurn),Math.cos(turn-referenceTurn));
                    double h=(Math.max(1,reference.profiles().get(0).source().anchorScreen().distance(reference.profiles().get(1).source().anchorScreen()))
                        +Math.max(1,reference.profiles().get(1).source().anchorScreen().distance(reference.profiles().get(2).source().anchorScreen())))/2;
                    assertEquals(domain.rows().get(2).accelerationWeight()*Math.pow(residual/Math.max(1,h),2),result.t(),1e-14);
                    for(var occurrence:result.emitted()) {
                        assertEquals(binding.nativePoint(occurrence.row(),occurrence.offsetPx()),occurrence.raster());
                        assertEquals(f.input.context().metricAt(binding.measurementPoint(occurrence.row(),occurrence.offsetPx())),occurrence.metric());
                    }
                }
            }
        }
    }
    private static double turn(RasterPoint a,RasterPoint b,RasterPoint c) {
        double turn=Math.atan2(c.y()-b.y(),c.x()-b.x())-Math.atan2(b.y()-a.y(),b.x()-a.x());
        return Math.atan2(Math.sin(turn),Math.cos(turn));
    }
    @Test void originalR4MetadataRetainsAllNativeStatesAndFrozenSourceIdentitiesWithoutAdmission() throws Exception {
        try(var f=new NativeAOriginalR4Fixture();var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            // Old native reference setup only. Never instantiate the optional solver/evaluator for R4.
            var reference=new CorridorAwareTracker().trackDetailed(binding.profiles().levelZeroProfiles(),1,JunctionContext.empty());
            var selected=reference.candidates().get(0);
            var track=reference.tracks().stream().filter(t->t.id().equals(selected.id())).findFirst().orElseThrow();
            var domain=new CorridorCenterlineOptimizer().nativeStateDomain(track,reference.profiles(),1,JunctionContext.empty(),
                reference.scaleEvidence(),reference.tubes().get(selected.id()),reference.optimizations().get(selected.id()).endpointApproaches());
            assertEquals(List.of(16,16,14,14,14,14,13,13,11,13,13,14,14,14,14,16,16),
                domain.rows().stream().map(row->row.states().size()).toList());
            var stateIdentity=new StringBuilder();int states=0,rowPairs=0,pairs=0,ordinary=0;
            for(var row:domain.rows())for(var state:row.states()) {
                states++;stateIdentity.append(binding.retainedRow(row.rowIndex())).append(':').append(state.stateId()).append(':')
                    .append(Double.toHexString(state.offsetPx())).append('\n');
            }
            for(int from=0;from<17;from++)for(int to=from+1;to<17;to++) {
                if(to-from-1>16||binding.sourceSpanUpperMeters(from,to)>20)continue;
                rowPairs++;int product=domain.rows().get(from).states().size()*domain.rows().get(to).states().size();pairs+=product;
                if(to==from+1)ordinary+=product;
            }
            assertEquals(239,states);assertEquals(133,rowPairs);assertEquals(26098,pairs);assertEquals(3124,ordinary);
            assertEquals(22974,pairs-ordinary);
            assertEquals("fa75b00a4411ad8ef0b7afaea56c2d334ac3f94a48fe0516a1742cd07caa21ae",hash(stateIdentity.toString()));
            var sourceIdentity=new StringBuilder();
            for(var row:f.input.context().observations())sourceIdentity.append(row.index()).append(':')
                .append(Double.toHexString(f.input.context().sourceChainageMeters(row.index()))).append(':')
                .append(Double.toHexString(row.location().rasterPoint().x())).append(':')
                .append(Double.toHexString(row.location().rasterPoint().y())).append('\n');
            assertEquals("da6deb4bd377b6e0e017b4b4b2a6a8ad34c50bc55e59c032826e2927afe1b3bd",hash(sourceIdentity.toString()));
            assertEquals("823ced3888e984084a98be62b6d1d73ae4762f3030661670a73020efa6428c17",hash(Arrays.toString(f.pixels)));
            var c=f.input.context();
            assertEquals("a3cf514a354cb99fffe0438e52a099aa98887c86e34e5a52bb1d7fb0a56d8e58",hash(c.sourceIdentity()+"|"+c.evidenceContentHash()
                +"|"+c.requestIdentity()+"|"+c.origin()+"|"+c.eligibility()+"|"+c.policyId()));
            assertEquals(0,f.input.workBudget().snapshot().pairVisits(),"Metadata must not execute the optional admission graph");
        }
    }
    private static String hash(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void actualAEntryUsesOnlyOriginalThreeRowsAndRetainedEndpointOrdinals() {
        try(var f=new SmallFrame();var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            var original=new CorridorAwareTracker().trackDetailed(binding.profiles().levelZeroProfiles(),1,JunctionContext.empty());
            try(var result=new CorridorAwareTracker().trackOptionalEmission(f.input,binding,JunctionContext.empty(),List.of(),
                    GeometryCleanupConfig.disabled(),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
                assertEquals(original.tracks().stream().map(CorridorTrack::id).toList(),result.trackIds());
                for(var candidate:result.results()) {
                    assertEquals(Set.of(0,1,2),candidate.nativeStates().stream().map(NativeAEmissionSolver.NativeStateInventory::row)
                        .collect(java.util.stream.Collectors.toSet()));
                    assertEquals(4,candidate.costs().stream().mapToDouble(NativeAEmissionSolver.SelectedCost::sourceSpanMeters).sum(),0);
                    assertEquals(0,candidate.cost(),1e-7);
                }
            }
        }
    }
    @Test void originalRowsAreBoundWithoutPromotingThePaddedMeasurementRows() {
        try(var f=new SmallFrame();var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            assertEquals(3,binding.profiles().levelZeroProfiles().size());assertEquals(5,f.input.context().observations().size());
            assertEquals(Map.of(1,0,2,1,3,2),binding.originalOrdinalByContextRow());
            for(int row=0;row<3;row++) {
                assertEquals(row+1,binding.retainedRow(row));
                assertSame(f.input.profiles().levelZeroProfiles().get(row+1),binding.profiles().levelZeroProfiles().get(row));
                assertEquals(f.input.context().sourceChainageMeters(row+1),binding.sourceChainageMeters(row),0);
            }
            assertEquals(1000.25,f.input.context().profileChainage().sourceOriginGroundMeters(),0);
            assertEquals(4,binding.sourceSpanUpperMeters(0,2),0);
            assertThrows(IllegalArgumentException.class,()->binding.originalRow(0));
        }
    }

    @Test void originalGeometryRejectsForeignAndStaleLifetimesWithoutRetainingStorage() {
        try(var f=new SmallFrame();var other=new SmallFrame()) {
            long baseline=f.ledger.currentBytes();
            try(var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
                assertThrows(IllegalArgumentException.class,()->binding.requireCurrent(other.input));
                var normal=binding.profiles().levelZeroProfiles().get(0).normalScreen();double x=normal.x;
                normal.x+=.125;
                assertThrows(IllegalStateException.class,()->binding.requireCurrent(f.input));normal.x=x;
                binding.requireCurrent(f.input);binding.close();
                assertThrows(IllegalArgumentException.class,()->binding.requireCurrent(f.input));
            }
            assertEquals(baseline,f.ledger.currentBytes());
        }
    }

    @Test void originalR4KeepsExactNativeCoordinatesBeforeCropTranslation() {
        try(var f=new NativeAOriginalR4Fixture();var binding=NativeAEmissionBinding.fromOriginalGeometry(f.input)) {
            assertEquals(17,binding.profiles().levelZeroProfiles().size());assertEquals(19,f.input.context().observations().size());
            boolean nonAssociative=false;
            for(int row=0;row<17;row++) {
                assertSame(f.source.profiles().get(row+1),binding.profiles().levelZeroProfiles().get(row));
                for(double offset:new double[]{.1,.3,1.1,3.7,9.7}) {
                    var original=f.source.profiles().get(row+1);var point=binding.measurementPoint(row,offset);
                    assertEquals((original.anchorScreen().x+original.normalScreen().x*offset)-18,point.x(),0);
                    assertEquals((original.anchorScreen().y+original.normalScreen().y*offset)-17,point.y(),0);
                    var retained=f.input.profiles().levelZeroProfiles().get(row+1);
                    nonAssociative|=Double.doubleToLongBits(point.x())!=Double.doubleToLongBits(retained.anchorScreen().x+retained.normalScreen().x*offset)
                        ||Double.doubleToLongBits(point.y())!=Double.doubleToLongBits(retained.anchorScreen().y+retained.normalScreen().y*offset);
                }
            }
            assertTrue(nonAssociative,"The fixture must expose an actual IEEE crop-operation-order difference");
        }
    }

    static final class SmallFrame implements AutoCloseable {
        final AttemptMemoryLedger ledger=AttemptMemoryLedger.production();
        final AttemptMemoryLedger.Owner root=ledger.rootOwner();
        final SampledEmissionInput input;
        SmallFrame(){this(false,CancellationProbe.NONE);}
        SmallFrame(boolean curved){this(curved,CancellationProbe.NONE);}
        SmallFrame(boolean curved,CancellationProbe cancellation) {
            int w=40,h=40;double[] values=new double[w*h];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)values[y*w+x]=Math.max(0,1-Math.abs(y-20)/3.);
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),
                new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.02));
            var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
            var field=new ScalarEvidenceField(w,h,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
            var evidence=new EvidenceSnapshot("a-binding-small",frame,transform,EvidenceResolution.nativeSource(1,1),
                MetricRegion.rectangle(1,1,38,38),MetricRegion.rectangle(-.5,-.5,39.5,39.5),Map.of("native",field),"public-synthetic");
            var locations=new ArrayList<DetachedProfileSamplingLocation>();var distances=new ArrayList<Double>();
            double distance=0;RasterPoint previous=null;
            for(int row=0;row<5;row++) {
                var raster=new RasterPoint(10+row*2,curved&&row==2?21:20);var metric=transform.pixelCenterToMetric(raster.x(),raster.y());
                if(previous!=null)distance+=Math.hypot(raster.x()-previous.x(),raster.y()-previous.y());
                distances.add(distance);locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(metric),metric,raster,frame,transform,distance));
                previous=raster;
            }
            var trace=new CorridorTraceInput(locations,1);var chainage=new ProfileChainage(distances,distance,1000.25);
            var budget=new EmissionWorkBudget(TraceBudgets.defaults(),cancellation);
            try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"native",trace,chainage,5,budget,root)) {
                var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"native",trace.profileLocations(),5,1,1,budget,permit);
                var geometry=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(1,2,3),1,List.of());
                input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,geometry);
            }
        }
        @Override public void close(){input.close();root.close();assertEquals(0,ledger.currentBytes());}
    }
}
