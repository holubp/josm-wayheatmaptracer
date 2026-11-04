package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class NativeAScalarPreparationTest {
    @Test void aCorruptedTooLargePrivateQCannotJustifyClosure() throws Exception {
        try(var fixture=NativeARefusalReuseTest.fixture(CancellationProbe.NONE,false);
                var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var evaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(engine.actual,"evaluator");var arc=engine.arc(1,3,false);
            var token=evaluator.openNativeScalarPreparations(engine.provider,engine.arcs.size()).prepare(arc,endpoint(engine,arc.fromId()),endpoint(engine,arc.toId()));
            assertEquals(0,token.getClass().getConstructors().length,"No public raw Result/Q token issuer exists");
            var q=token.getClass().getDeclaredField("q");q.setAccessible(true);q.setDouble(token,token.nativeQ()+1);
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,()->token.requireFor(engine.provider,arc));
        }
    }
    @Test void actualScalarQUsesNativeGraphPitchInsteadOfTheProbeMinimum() throws Exception {
        var resolution=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution.Kind.NATIVE_SOURCE,
            java.util.OptionalDouble.of(2),1,java.util.List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution.PitchSample(0,java.util.OptionalDouble.of(2),1),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution.PitchSample(6,java.util.OptionalDouble.of(1),1)));
        try(var fixture=new NativeAEmissionSolverTest.Fixture(java.util.List.of(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(8,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(10,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(12,20),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(14,20)),
                (x,y)->.9*Math.max(0,1-Math.abs(y-20)/5.),7,
                new EmissionWorkBudget(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),CancellationProbe.NONE),0,false,resolution);
                var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var evaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(engine.actual,"evaluator");var arc=engine.arc(1,3,false);
            var from=endpoint(engine,arc.fromId());var to=endpoint(engine,arc.toId());
            var token=evaluator.openNativeScalarPreparations(engine.provider,engine.arcs.size()).prepare(arc,from,to);
            var receipt=evaluator.evaluateWithNativeScalar(from,to,token);
            assertEquals(Status.ADMITTED,receipt.status());assertEquals(1,receipt.minimumNativePitchMeters(),0);
            assertEquals(2,fixture.input.context().nativePitchMeters().orElseThrow(),0);
            double q=arc.sourceSpanMeters()/2*(1.0-token.scalar().mean().orElseThrow());
            assertEquals(Double.doubleToLongBits(q),Double.doubleToLongBits(token.nativeQ()));
            assertNotEquals(arc.sourceSpanMeters()/receipt.minimumNativePitchMeters()*(1-token.scalar().mean().orElseThrow()),token.nativeQ());
        }
    }
    @Test void foreignProvidersEqualArcsAndEqualStatesCannotIssueOrConsumeAFact() throws Exception {
        try(var fixture=NativeARefusalReuseTest.fixture(CancellationProbe.NONE,false);
                var other=NativeARefusalReuseTest.fixture(CancellationProbe.NONE,false);
                var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1);
                var foreign=new NativeARefusalReuseTest.NativeEngine(other,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var evaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(engine.actual,"evaluator");
            var otherEvaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(foreign.actual,"evaluator");
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,
                ()->evaluator.openNativeScalarPreparations(foreign.provider,engine.arcs.size()));
            var cache=evaluator.openNativeScalarPreparations(engine.provider,engine.arcs.size());
            var arc=engine.arc(1,3,false);var from=endpoint(engine,arc.fromId());var to=endpoint(engine,arc.toId());
            var equalArc=foreign.arc(1,3,false);assertEquals(arc,equalArc);assertNotSame(arc,equalArc);
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,()->cache.prepare(equalArc,from,to));
            var equalFrom=new RowState(from.row(),from.nativeStateId(),from.offsetRasterPixels(),from.sourceClass(),from.peakOwner());
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,()->cache.prepare(arc,equalFrom,to));
            var token=cache.prepare(arc,from,to);
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,
                ()->token.requireFor(foreign.provider,equalArc));
            assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,
                ()->otherEvaluator.evaluateWithNativeScalar(endpoint(foreign,equalArc.fromId()),endpoint(foreign,equalArc.toId()),token));
            assertEquals(Status.UNAVAILABLE,evaluator.evaluateWithNativeScalar(equalFrom,to,token).status(),
                "Normal foreign-state priority precedes token consumption");
        }
    }
    @Test void aPreparedHitAndFullConsumptionBothRevalidateActualSource() throws Exception {
        try(var fixture=NativeARefusalReuseTest.fixture(CancellationProbe.NONE,false);
                var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var evaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(engine.actual,"evaluator");
            var cache=evaluator.openNativeScalarPreparations(engine.provider,engine.arcs.size());
            var arc=engine.arc(1,3,false);var from=endpoint(engine,arc.fromId());var to=endpoint(engine,arc.toId());
            var token=cache.prepare(arc,from,to);var profile=fixture.input.profiles().levelZeroProfiles().get(1);
            var point=profile.normalScreen();double saved=point.x;point.x+=.125;Runnable restore=()->point.x=saved;
            try {
                assertThrows(IllegalStateException.class,()->cache.prepare(arc,from,to));
                assertThrows(IllegalStateException.class,()->evaluator.evaluateWithNativeScalar(from,to,token));
                assertEquals(0,cache.hits());
            }finally{restore.run();}
            assertThrows(UnsupportedOperationException.class,()->profile.intensitySamples().set(0,profile.intensitySamples().get(1)));
            double anchor=profile.anchorScreen().x;profile.anchorScreen().x+=.125;
            assertEquals(anchor,profile.anchorScreen().x,0,"The original sampling anchor is immutable");
            assertSame(token,cache.prepare(arc,from,to));engine.close();
            assertThrows(IllegalStateException.class,token::scalar);
            assertThrows(IllegalStateException.class,()->cache.prepare(arc,from,to));
        }
    }
    @ParameterizedTest @ValueSource(strings={"work","pair","cancel","memory"})
    void terminalBeforeARealFactHitRemainsStickyAndReleasesTheWholeCache(String kind) throws Exception {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        try(var fixture=NativeARefusalReuseTest.fixture(cancelled::get,false)) {
            long baseline=fixture.ledger.currentBytes();
            try(var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
                var evaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(engine.actual,"evaluator");
                var cache=evaluator.openNativeScalarPreparations(engine.provider,engine.arcs.size());
                var arc=engine.arc(1,3,false);var from=endpoint(engine,arc.fromId());var to=endpoint(engine,arc.toId());cache.prepare(arc,from,to);
                var budget=fixture.input.workBudget();org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Reservation pressure=null;
                if(kind.equals("work"))budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.snapshot().workLimit()-budget.snapshot().workUsed());
                if(kind.equals("pair"))budget.charge(EmissionWorkBudget.Operation.TRANSITION,budget.snapshot().pairLimit()-budget.snapshot().pairVisits());
                if(kind.equals("cancel"))cancelled.set(true);
                // Authority validation reserves bounded scratch even on a cached fact.
                if(kind.equals("memory"))pressure=fixture.owner.reserve(fixture.ledger.limitBytes()-fixture.ledger.currentBytes());
                try {
                    var failure=assertThrows(RuntimeException.class,()->cache.prepare(arc,from,to));
                    assertSame(failure,assertThrows(RuntimeException.class,fixture.input::requireOpenAndUnchanged));
                    assertSame(failure,assertThrows(RuntimeException.class,()->cache.prepare(arc,from,to)));
                    assertEquals(0,cache.hits());
                }finally{if(pressure!=null)pressure.close();}
            }
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }
    static RowState endpoint(NativeARefusalReuseTest.NativeEngine engine,int id) throws Exception {
        return (RowState)NativeARefusalReuseTest.method(engine.vertices.get(id),"endpoint");
    }
    @Test void actualOwnedScalarIsPreparedBeforeModesAndReusedByIdentityAtTheOriginalBoundary() throws Exception {
        try(var fixture=NativeARefusalReuseTest.fixture(CancellationProbe.NONE,false);
                var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var arc=engine.arc(1,3,false);var evaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(engine.actual,"evaluator");
            var from=(RowState)NativeARefusalReuseTest.method(engine.vertices.get(arc.fromId()),"endpoint");
            var to=(RowState)NativeARefusalReuseTest.method(engine.vertices.get(arc.toId()),"endpoint");
            var cache=evaluator.openNativeScalarPreparations(engine.provider,engine.arcs.size());
            long modes=fixture.input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION);
            var token=cache.prepare(arc,from,to);assertNotNull(token);assertTrue(token.hasFiniteNativeQ());
            assertEquals(modes,fixture.input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION));
            var receipt=evaluator.evaluateWithNativeScalar(from,to,token);assertEquals(Status.ADMITTED,receipt.status());
            assertSame(token.scalar(),receipt.scalar().orElseThrow());
            assertEquals(Double.doubleToLongBits(CompetitiveAdmissionExecutor.Admission.measured(arc.sourceSpanMeters(),
                fixture.input.context().nativePitchMeters().orElseThrow(),receipt.scalar().orElseThrow(),0,0,0,receipt).q()),
                Double.doubleToLongBits(token.nativeQ()));
            assertSame(token,cache.prepare(arc,from,to));
        }
    }

    @Test void unavailableIntegralRemainsUnknownAndTheFullOriginalReasonConsumesTheSameResultWithoutNewReads() throws Exception {
        try(var fixture=NativeARefusalReuseTest.fixture(CancellationProbe.NONE,true);
                var engine=new NativeARefusalReuseTest.NativeEngine(fixture,NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_REFUSAL_REUSE_V1)) {
            var arc=engine.arc(1,3,false);var evaluator=(NativeConnectorEvaluator)NativeARefusalReuseTest.field(engine.actual,"evaluator");
            var from=(RowState)NativeARefusalReuseTest.method(engine.vertices.get(arc.fromId()),"endpoint");
            var to=(RowState)NativeARefusalReuseTest.method(engine.vertices.get(arc.toId()),"endpoint");
            var token=evaluator.openNativeScalarPreparations(engine.provider,engine.arcs.size()).prepare(arc,from,to);
            assertNotNull(token);assertFalse(token.hasFiniteNativeQ());
            long cells=fixture.input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.CELL_QUERY);
            var receipt=evaluator.evaluateWithNativeScalar(from,to,token);
            assertEquals(Status.UNAVAILABLE,receipt.status());assertEquals(Reason.SCALAR_SUPPORT,receipt.reason());
            assertSame(token.scalar(),receipt.scalar().orElseThrow());
            assertEquals(cells,fixture.input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.CELL_QUERY));
        }
    }
}
