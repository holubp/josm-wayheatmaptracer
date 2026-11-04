package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator;

class NativeAEndpointOwnershipTest {
    @Test void nativeParentUpperPortUsesItsDirectUpperCoreAndRejectsTheWrongChild() throws Exception {
        var anchors=List.of(new RasterPoint(10,20),new RasterPoint(12,20),new RasterPoint(14,20));
        try(var fixture=new NativeAEmissionSolverTest.Fixture(anchors,(x,y)-> {
                double d=y-20;
                if(Math.abs(d)>5)return 0;
                return .70+.30*Math.max(Math.exp(-.5*Math.pow((d-2)/.45,2)),Math.exp(-.5*Math.pow((d+2)/.45,2)));
            },12)) {
            var baseline=new CorridorAwareTracker().trackDetailed(fixture.input.profiles(),1,JunctionContext.empty());
            assertTrue(baseline.tracks().stream().anyMatch(CorridorTrack::parent),()->"Native tracks="+baseline.tracks());
            var upper=baseline.tracks().stream().filter(track->!track.parent()).max(java.util.Comparator.comparingDouble(
                track->track.points().get(0).band().centerOffsetPx())).orElseThrow();
            var lower=baseline.tracks().stream().filter(track->!track.parent()).min(java.util.Comparator.comparingDouble(
                track->track.points().get(0).band().centerOffsetPx())).orElseThrow();
            var parent=baseline.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
            assertTrue(upper.points().get(0).band().coreMinPx()>lower.points().get(0).band().coreMaxPx());
            var originalPeaks=fixture.input.profiles().levelZeroProfiles().get(0).peaks();
            assertEquals(1,originalPeaks.size(),"Legacy merges this shared shoulder; its center cannot stand in for both A cores");
            assertTrue(originalPeaks.get(0).offsetPx()<upper.points().get(0).band().coreMinPx());
            assertTrue(upper.points().get(0).band().shoulderMinPx()<=originalPeaks.get(0).offsetPx(),
                "Upper native child must share a shoulder with the first lower legacy peak");
            try(var result=new CorridorAwareTracker().trackOptionalEmission(fixture.input,JunctionContext.empty(),List.of(),
                    GeometryCleanupConfig.disabled(),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
                for(var track:List.of(upper,parent)) {
                    var candidate=result.results().get(result.trackIds().indexOf(track.id()));
                    var rows=states(candidate);var owners=inventory(candidate);
                    var from=nearest(rows,0,upper.points().get(0).band().centerOffsetPx());
                    var to=nearest(rows,2,upper.points().get(2).band().centerOffsetPx());
                    try(var evaluator=NativeConnectorEvaluator.forNativeA(fixture.input,new NativeConnectorEvaluator.Domain(rows,List.of()),
                            NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                            NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1,owners)) {
                        var receipt=evaluator.evaluate(from,to);
                        assertEquals(NativeConnectorEvaluator.Status.ADMITTED,receipt.status(),track.id()+":"+receipt.reason());
                        assertNull(from.peakOwner());
                        if(track.parent()) {
                            var midpointFrom=nearest(rows,0,0);var midpointTo=nearest(rows,2,0);
                            assertEquals(0,midpointFrom.offsetRasterPixels(),0);
                            var midpoint=evaluator.evaluate(midpointFrom,midpointTo);
                            assertFalse(midpoint.status()==NativeConnectorEvaluator.Status.ADMITTED,
                                "A parent union center cannot manufacture a unique fresh port");
                            assertTrue(midpoint.reason()==NativeConnectorEvaluator.Reason.NO_COMPLETE_MODE
                                ||midpoint.reason()==NativeConnectorEvaluator.Reason.AMBIGUOUS_MODE,
                                ()->"Expected absent or ambiguous fresh port, got "+midpoint.reason());
                        }
                    }
                }
                var lowerCandidate=result.results().get(result.trackIds().indexOf(lower.id()));
                var lowerRows=states(lowerCandidate);
                var wrongFrom=nearest(lowerRows,0,upper.points().get(0).band().centerOffsetPx());
                var wrongTo=nearest(lowerRows,2,upper.points().get(2).band().centerOffsetPx());
                assertTrue(wrongFrom.offsetRasterPixels()>lower.points().get(0).band().coreMaxPx()+1.5,
                    "Wrong-child state must be outside the lower core ownership reach");
                try(var evaluator=NativeConnectorEvaluator.forNativeA(fixture.input,new NativeConnectorEvaluator.Domain(lowerRows,List.of()),
                        NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                        NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1,inventory(lowerCandidate))) {
                    var receipt=evaluator.evaluate(wrongFrom,wrongTo);
                    assertEquals(NativeConnectorEvaluator.Reason.ENDPOINT_OWNERSHIP,receipt.reason());
                }
            }
        }
    }

    @Test void issuedAOwnersRejectForeignRowsInputsAndClosedLifetimes() throws Exception {
        try(var fixture=new NativeAEmissionSolverTest.Fixture(3);var foreign=new NativeAEmissionSolverTest.Fixture(3);
            var result=new CorridorAwareTracker().trackOptionalEmission(fixture.input,JunctionContext.empty(),List.of(),
                GeometryCleanupConfig.disabled(),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
            var candidate=result.results().get(0);var rows=states(candidate);var owners=inventory(candidate);
            var row=rows.get(0);
            var copy=new NativeConnectorEvaluator.RowState(row.row(),row.nativeStateId(),row.offsetRasterPixels(),row.sourceClass(),null);
            assertEquals(row,copy);assertNull(owners.ownerFor(copy));
            assertThrows(IllegalArgumentException.class,()->NativeConnectorEvaluator.forNativeA(fixture.input,
                new NativeConnectorEvaluator.Domain(List.of(copy),List.of()),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1,owners));
            assertThrows(IllegalArgumentException.class,()->NativeConnectorEvaluator.forNativeA(foreign.input,
                new NativeConnectorEvaluator.Domain(rows,List.of()),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1,owners));
            var wrongRow=new NativeConnectorEvaluator.RowState(row.row()+1,row.nativeStateId(),row.offsetRasterPixels(),row.sourceClass(),null);
            assertNull(owners.ownerFor(wrongRow));
            candidate.close();
            assertFalse(owners.belongsTo(fixture.input));
            assertThrows(IllegalArgumentException.class,()->NativeConnectorEvaluator.forNativeA(fixture.input,
                new NativeConnectorEvaluator.Domain(rows,List.of()),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1,owners));
        }
    }

    private static Object engine(NativeAEmissionSolver.Result result) throws Exception {
        var field=NativeAEmissionSolver.Result.class.getDeclaredField("engine");field.setAccessible(true);return field.get(result);
    }
    private static NativeAEmissionSolver.NativeEndpointInventory inventory(NativeAEmissionSolver.Result result) throws Exception {
        var engine=engine(result);var field=engine.getClass().getDeclaredField("endpointOwners");field.setAccessible(true);
        return (NativeAEmissionSolver.NativeEndpointInventory)field.get(engine);
    }
    private static List<NativeConnectorEvaluator.RowState> states(NativeAEmissionSolver.Result result) throws Exception {
        var engine=engine(result);var field=engine.getClass().getDeclaredField("vertices");field.setAccessible(true);
        var values=(List<?>)field.get(engine);var rows=new ArrayList<NativeConnectorEvaluator.RowState>();
        for(Object vertex:values) {
            var endpoint=vertex.getClass().getDeclaredMethod("endpoint");endpoint.setAccessible(true);
            if(endpoint.invoke(vertex) instanceof NativeConnectorEvaluator.RowState row)rows.add(row);
        }
        return List.copyOf(rows);
    }
    private static NativeConnectorEvaluator.RowState nearest(List<NativeConnectorEvaluator.RowState> rows,int row,double offset) {
        return rows.stream().filter(state->state.row()==row).min(java.util.Comparator.comparingDouble(
            state->Math.abs(state.offsetRasterPixels()-offset))).orElseThrow();
    }
    @Test void observedNativeABandDoesNotRequireAnUnrelatedLegacyPeak() {
        var anchors=List.of(new RasterPoint(10,20),new RasterPoint(12,20),new RasterPoint(14,20));
        try(var fixture=new NativeAEmissionSolverTest.Fixture(anchors,
                (x,y)->.10*Math.max(0,1-Math.abs(y-20)/3.0),5)) {
            for(var profile:fixture.input.profiles().levelZeroProfiles())
                assertTrue(profile.peaks().stream().allMatch(p->p.syntheticCenter()),
                    "Fixture must have no genuine Legacy peak; native A uses relative scalar bands");
            var nativeReference=new CorridorAwareTracker().trackDetailed(fixture.input.profiles(),1,JunctionContext.empty());
            assertFalse(nativeReference.tracks().isEmpty());
            assertTrue(nativeReference.profiles().stream().allMatch(p->p.bands().stream().anyMatch(CorridorBand::hasMeasuredCenter)));
            try(var result=new CorridorAwareTracker().trackOptionalEmission(fixture.input,JunctionContext.empty(),List.of(),
                    GeometryCleanupConfig.disabled(),NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
                assertFalse(result.results().isEmpty());
                var candidate=result.results().get(0);
                assertTrue(candidate.nativeStates().stream().allMatch(state->state.sourceClass()==NativeConnectorEvaluator.SourceClass.OBSERVED));
                assertTrue(candidate.closure().measuredTransitions().stream().anyMatch(row->row.admitted()
                    &&candidate.potentialInventory().get(row.arcId()).optional()),
                    ()->"A complete native band/fresh port must not be INVALID_STATE because Legacy has no peak; reasons="
                        +candidate.closure().measuredTransitions().stream().filter(row->!row.admitted()).map(row->row.reason()).distinct().toList());
            }
        }
    }
}
