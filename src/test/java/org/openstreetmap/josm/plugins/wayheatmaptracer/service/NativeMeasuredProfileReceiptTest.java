package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.lang.reflect.Modifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.Mode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeMeasuredProfileReceiptTest {
    @Test void actualProfileOwnershipHasAnExplicitEvaluatorFactoryWithoutEnablingAConsumer() {
        assertDoesNotThrow(() -> NativeConnectorEvaluator.class.getMethod("openAffinityProfiles", SampledEmissionInput.class),
            "M1 must issue source-owned profiles through the evaluator; public diagnostic Probe records confer no ownership");
    }
    @Test void actualModeNumericsAndNonunitPhysicalSupportStayDistinct() {
        withInput(input -> {
            try (var session = NativeConnectorEvaluator.openAffinityProfiles(input)) {
                var center = new MetricPoint(46, 64); var normal = new MetricPoint(0, 2);
                var profile = session.query(5, center, normal);
                assertNotNull(profile, "A real evaluator query must issue a retained numeric profile, not a public Probe-shaped claim");
                assertFalse(profile.modes().isEmpty());
                for (var mode : profile.modes()) {
                    double c = mode.accepted().mode().centerMeters();
                    assertEquals(Double.doubleToRawLongBits(c), Double.doubleToRawLongBits(mode.numericCenter()));
                    var expected = new MetricPoint(center.xMeters() + c * normal.xMeters(), center.yMeters() + c * normal.yMeters());
                    assertEquals(expected, mode.metricPosition());
                    assertEquals(input.context().rasterAt(expected), mode.rasterPosition());
                    assertEquals(input.context().metricAt(mode.rasterPosition()), mode.forwardMetricPosition());
                    assertTrue(mode.physicalSigmaUpper() >= 2 * mode.accepted().mode().localizationSigmaMeters());
                }
            }
        });
    }
    @Test void modeCenterOneUlpEscapeIsNotClampedAndPublicModesCannotMintTokens() throws Exception {
        var mode=new Mode(.1-.25,.1+.25,.25,1,1,List.of(.1),List.of(.1));
        assertEquals(.09999999999999999,mode.centerMeters());
        var method=NativeMeasuredProfileReceipt.Session.class.getDeclaredMethod("displace",MetricPoint.class,MetricPoint.class,double.class);
        method.setAccessible(true);
        var actual=(MetricPoint)method.invoke(null,new MetricPoint(0,0),new MetricPoint(1,0),mode.centerMeters());
        assertEquals(Double.doubleToRawLongBits(mode.centerMeters()),Double.doubleToRawLongBits(actual.xMeters()));
        assertNotEquals(Double.doubleToRawLongBits(.1),Double.doubleToRawLongBits(actual.xMeters()));
        for(var constructor:NativeMeasuredProfileReceipt.OwnedModeGeometry.class.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    @Test void repeatedExactQuerySharesExtractionAndRetainsActualPointLaw() {
        withInput(input -> {
            try(var session=NativeConnectorEvaluator.openAffinityProfiles(input)){
                var c=new MetricPoint(46,64);var n=new MetricPoint(0,2);
                var profile=session.query(5,c,n);long modes=input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION);
                assertSame(profile,session.query(5,c,n));
                assertEquals(modes,input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION));
                assertEquals(-25,profile.probe().samples().get(0).offsetMeters());
                assertEquals(25,profile.probe().samples().get(profile.probe().samples().size()-1).offsetMeters());
                for(var q:profile.queries()){
                    double u=q.sample().offsetMeters();assertEquals(new MetricPoint(46,64+u*2),q.metric());
                    assertTrue(q.scalarQueried());assertTrue(q.acquiredQueried());
                    assertEquals(input.context().rasterAt(q.metric()),q.raster().orElseThrow());
                    assertEquals(input.context().nativeGrid().toGrid(q.raster().orElseThrow()),q.nativeGrid().orElseThrow());
                }
                for(var mode:profile.modes())assertTrue(mode.support().presentCellVisits().isEmpty(),"Uncertified detached inverse must not report measured-zero cell support");
                assertThrows(UnsupportedOperationException.class,()->profile.modes().clear());
            }
        });
    }
    @Test void physicalFamilyProjectionUsesDualBasisForBothOrientations() {
        withInput(input->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(input)){
            for(double sign:new double[]{-1,1}){
                var family=session.freezeFamily(new MetricPoint(10,20),new MetricPoint(3,0),new MetricPoint(0,sign*2),0,3);
                var p=family.project(new MetricPoint(16,20+sign*8));
                assertTrue(p.minimumS()<=2&&p.maximumS()>=2);assertTrue(p.minimumU()<=4&&p.maximumU()>=4);
                assertEquals(sign,family.exactDeterminant().signum());
                assertFalse(family.continuousTransportCertified(),"Detached frames are not silently given a managed transport certificate");
            }
            assertThrows(IllegalArgumentException.class,()->session.freezeFamily(new MetricPoint(0,0),new MetricPoint(1,1),new MetricPoint(2,2),0,1));
        }});
    }
    @Test void sourcePointIsNotNativeStateAuthorityAndForgedEqualStateIsRejected() {
        withInput(input->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(input);var domainOwner=input.openExecutionOwner("m1-native-identity-test")){
            var domain=new LegacyEmissionDomain(input.legacyBinding().orElseThrow(),input.profiles().levelZeroProfiles(),List.of(-4.0,4.0),input.workBudget(),domainOwner);
            var state=domain.observedAt(5).get(0);var point=domain.rasterPoint(state);
            var profile=session.metricAnchoredQuery(state.evaluatorState().row(),point,new MetricPoint(0,1));
            var observed=session.observePoint(profile,point);assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,observed.availability());
            assertEquals(point,observed.actualRaster());assertTrue(observed.strictScalar().isPresent());assertTrue(observed.bridge().isPresent());
            var owned=session.bindNativeState(profile,domain,state);assertSame(state,owned.state());owned.validate();
            var forged=new LegacyEmissionDomain.State(state.originalRow(),state.nativeStateId(),state.kind(),state.offsetOriginId(),state.offsetRasterPixels(),state.peakOwner(),state.evaluatorState());
            assertEquals(state,forged);assertNotSame(state,forged);
            assertThrows(IllegalArgumentException.class,()->session.bindNativeState(profile,domain,forged));
            assertThrows(IllegalArgumentException.class,()->session.bindNativeState(session.query(0,new MetricPoint(46,64),new MetricPoint(0,1)),domain,state));
        }});
    }
    @Test void actualNativeOwnershipPreservesOriginalToMeasurementPeakAliasGraph() {
        var source=PhysicalGeometryFixture.r4SyntheticFrame(4);
        var original=source.profiles().subList(1,18);var crop=LegacyEmissionBindingTest.r4Crop(source);
        var ledger=AttemptMemoryLedger.production();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        String field=crop.evidence().fields().keySet().iterator().next();
        try(var owner=ledger.rootOwner();var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(crop.evidence(),field,crop.input(),crop.chainage(),25,budget,owner)){
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(crop.evidence(),field,crop.input().profileLocations(),25,1,1,budget,permit);
            var mapping=java.util.stream.IntStream.range(1,18).boxed().toList();
            var truth=new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(source.fullScalarGrid(),18,17,1,0,List.of());
            var binding=OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit,retained,original,mapping,1,truth);
            try(var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
                var session=NativeConnectorEvaluator.openAffinityProfiles(input);var stateOwner=input.openExecutionOwner("m1-mapped-native-test")){
                var domain=new LegacyEmissionDomain(binding,original,List.of(0.0),budget,stateOwner);
                var state=domain.observedAt(0).get(0);assertNotSame(state.peakOwner(),state.evaluatorState().peakOwner());
                var p=domain.rasterPoint(state);var profile=session.metricAnchoredQuery(state.evaluatorState().row(),p,new MetricPoint(0,1));
                var actual=session.bindNativeState(profile,domain,state);assertSame(state,actual.state());actual.validate();
            }
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void invalidSamplesRemainPartialFactsWithoutObservedOwners() {
        withInput(true,input->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(input)){
            var profile=session.query(5,new MetricPoint(46,64),new MetricPoint(0,1));
            assertTrue(profile.probe().samples().stream().anyMatch(s->!s.valid()));
            assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,session.observePoint(profile,new RasterPoint(46,64)).availability());
        }});
    }
    @Test void refusedSamplesRetainUnqueriedAcquisitionAndScalarInsteadOfInventedMissingValues() {
        withInput(input->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(input)){
            var profile=session.query(0,new MetricPoint(1,64),new MetricPoint(-1,0));
            var refused=profile.queries().stream().filter(q->!q.originalRouteAuthorized()).toList();
            assertFalse(refused.isEmpty());
            for(var q:refused){assertFalse(q.acquiredQueried());assertFalse(q.scalarQueried());assertTrue(q.raster().isEmpty());assertFalse(q.sample().valid());}
            for(int i=0;i<profile.queries().size();i++)assertSame(profile.probe().samples().get(i),profile.queries().get(i).sample());
        }});
    }
    @Test void sourceFreshnessAndLifetimeAreValidatedAtIssuance() {
        withInput(input->{var session=NativeConnectorEvaluator.openAffinityProfiles(input);
            var profile=session.query(5,new MetricPoint(46,64),new MetricPoint(0,1));profile.validate();
            input.profiles().levelZeroProfiles().get(0).normalScreen().x+=.125;
            assertThrows(IllegalStateException.class,profile::validate);
            assertThrows(IllegalStateException.class,()->session.query(5,new MetricPoint(46,64),new MetricPoint(0,1)));
            session.close();assertThrows(IllegalStateException.class,profile::modes);
        });
    }
    @Test void foreignProfilesAndFamiliesCannotBeBorrowed() {
        withInput(input->{try(var a=NativeConnectorEvaluator.openAffinityProfiles(input);var b=NativeConnectorEvaluator.openAffinityProfiles(input)){
            var profile=a.query(5,new MetricPoint(46,64),new MetricPoint(0,1));
            assertThrows(IllegalArgumentException.class,()->b.observePoint(profile,new RasterPoint(46,64)));
            var family=a.freezeFamily(new MetricPoint(46,64),new MetricPoint(1,0),new MetricPoint(0,1),0,1);
            assertThrows(IllegalArgumentException.class,()->b.query(family,0,5));
        }});
    }
    @Test void stickyWorkFailureCannotBecomeAnEmptyOrCachedSuccessfulProfile() {
        withInput(input->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(input)){
            var profile=session.query(5,new MetricPoint(46,64),new MetricPoint(0,1));
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,Long.MAX_VALUE));
            assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,profile::validate));
            assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,()->session.query(5,new MetricPoint(46,64),new MetricPoint(0,1))));
        }});
    }
    private interface Check { void run(SampledEmissionInput input); }
    private static void withInput(Check check) {
        withInput(false,check);
    }
    private static void withInput(boolean missing,Check check) {
        var fixture = LegacyAffinityContractProbeTest.fixture(.6, missing, false);
        var ledger = AttemptMemoryLedger.production(); var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        String field = fixture.evidence().fields().keySet().iterator().next();
        try (var owner = ledger.rootOwner(); var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                fixture.evidence(), field, fixture.input(), fixture.chainage(), 25, budget, owner)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), field, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var mapping = java.util.stream.IntStream.range(0, fixture.input().profileLocations().size()).boxed().toList();
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, mapping, 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) { check.run(input); }
        }
        assertEquals(0, ledger.currentBytes());
    }
}
