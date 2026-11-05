package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ManagedAR1RegressionTest {
    @TempDir Path cache;

    @Test void distinctCapturedEndpointNodesAtTheSameCoordinateCannotPublishABacktrack() throws Exception {
        var projection=Projections.getProjectionByCode("EPSG:3857");
        var a=new LatLon(49.44,Math.nextUp(14.0));var base=projection.latlon2eastNorth(a);
        var coordinates=List.of(a,projection.eastNorth2latlon(new EastNorth(base.east()+12,base.north())),a);
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withGeographic(false,coordinates,10,"ridge",CancellationProbe.NONE,f->{
            assertNotSame(f.nodes().get(0),f.nodes().get(2));
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit)) {
                var endpoints=input.managedAEndpoints().orElseThrow().endpoints();
                assertEquals(endpoints.get(0).exactMetric(),endpoints.get(1).exactMetric());
                long before=f.ledger().currentBytes();
                var failure=assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,()->{
                    try(var result=new CorridorAwareTracker().trackManagedAEndpointEmission(input,
                            ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION)) {
                        assertFalse(result.results().isEmpty(),"The real native stripe must reach final shape validation");
                        assertFalse(result.results().get(0).emitted().isEmpty(),"A no-route result is not this regression");
                    }
                });
                assertTrue(failure.getMessage().contains("physical shape"));
                assertEquals(before,f.ledger().currentBytes(),"Refusal must release all execution leases");
            }
        });
    }

    @ParameterizedTest @ValueSource(strings={"partial-overlap","bend","switchback","straight"})
    void storedFinalPhysicalShapeChecksAdjacentOverlapWithoutBlockingSupportedTurns(String shape) throws Exception {
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        var coordinates=List.of(new LatLon(49.44,14),new LatLon(49.44,14.00005),new LatLon(49.44,14.0001));
        helper.withGeographic(false,coordinates,10,"ridge",CancellationProbe.NONE,f->{
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit);
                    var tracking=new CorridorAwareTracker().trackManagedAEndpointEmission(input,
                        ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION)) {
                var result=tracking.results().get(0);var original=result.emitted();
                var first=original.get(0);var last=original.get(original.size()-1);
                assertEquals(first.metric().yMeters(),last.metric().yMeters(),0,"Horizontal captured endpoints make collinearity exact");
                double x=first.metric().xMeters(),y=first.metric().yMeters(),length=last.metric().xMeters()-x;
                var points=new ArrayList<NativeAEmissionSolver.EmittedOccurrence>();points.add(first);
                var middle=shape.equals("partial-overlap")?List.of(new MetricPoint(x+2*length,y))
                    :shape.equals("bend")?List.of(new MetricPoint(x+length/2,y+length/2))
                    :shape.equals("switchback")?List.of(new MetricPoint(x,y+length),new MetricPoint(x+length,y+length))
                    :List.of(new MetricPoint(x+length/2,y));
                // Hold the owned native evidence path fixed and isolate the stored final physical-shape guard.
                for(var metric:middle)points.add(new NativeAEmissionSolver.EmittedOccurrence(first.vertexId(),first.row(),
                    first.nativeStateId(),first.occurrenceId(),first.raster(),metric,first.offsetPx()));
                points.add(last);
                var field=NativeAEmissionSolver.Result.class.getDeclaredField("emitted");field.setAccessible(true);
                field.set(result,List.copyOf(points));
                try {
                    if(shape.equals("partial-overlap"))assertThrows(CompetitiveAdmissionExecutor.InvalidCertificateException.class,result::validateManagedFinalShape);
                    else assertDoesNotThrow(result::validateManagedFinalShape);
                } finally { field.set(result,original); }
            }
        });
    }

    @ParameterizedTest @ValueSource(strings={"finish","fail"})
    void terminalCensusDuringSnapshotRetainsItsOriginalHeaderThroughCleanup(String terminal) throws Exception {
        var current=new AtomicReference<NativeAExecutionCensus>();var fired=new AtomicBoolean();
        var frozen=new AtomicReference<List<Object>>();
        var primary=new IllegalStateException("synthetic terminal callback");
        CancellationProbe callback=()->{
            var census=current.get();
            if(census!=null&&!fired.get()&&ManagedAEndpointPublicationTest.stack("CompetitiveAdmissionExecutor","snapshotResult")) {
                fired.set(true);
                if(terminal.equals("finish"))census.finish();else census.fail(primary);
                frozen.set(ManagedAEndpointPublicationTest.snapshot(census));
            }
            return false;
        };
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,callback,f->{
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit);
                    var census=NativeAExecutionCensus.open(input)) {
                current.set(census);long before=f.ledger().currentBytes();
                assertThrows(RuntimeException.class,()->{
                    try(var ignored=ManagedAEndpointExecutionTest.invoke(input,
                            NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1,census)){}
                });
                assertTrue(fired.get(),"Terminal callback must run inside real competitive snapshot construction");
                assertEquals(frozen.get(),ManagedAEndpointPublicationTest.snapshot(census),
                    "Cleanup must not refresh any terminal census field after subsequent charges");
                assertSame(terminal.equals("fail")?primary:null,census.failure());
                assertEquals(before,f.ledger().currentBytes(),"All execution leases must close after publication refusal");
                long frozenWork=census.workUsed(),frozenPairs=census.pairVisits();
                census.close();
                assertEquals(frozenWork,census.workUsed(),"Explicit lease release must also preserve terminal work");
                assertEquals(frozenPairs,census.pairVisits(),"Explicit lease release must also preserve terminal pair visits");
                assertTrue(f.ledger().currentBytes()<before,"Closing the census must release its own prepaid storage");
            }
        });
    }
}
