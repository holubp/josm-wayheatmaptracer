package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.RowState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Uses only states issued from actual extracted elementary native tracks. */
class ManagedAEndpointDegeneracyTest {
    @TempDir Path cache;
    @Test void everyActualGraphZeroFlagUsesExactPhysicalCoordinatesBeforeCachesWithoutInventingRepeatedStates()throws Exception{
        var projection=Projections.getProjectionByCode("EPSG:3857");
        var a=new LatLon(49.44,Math.nextUp(14.0));var base=projection.latlon2eastNorth(a);
        var coordinates=List.of(a,projection.eastNorth2latlon(new EastNorth(base.east()+4,base.north())),a,
            projection.eastNorth2latlon(new EastNorth(base.east(),base.north()+12)));
        var fixture=new ManagedAEndpointProducerTest();fixture.cache=cache;
        fixture.withGeographic(false,coordinates,4,"blob",CancellationProbe.NONE,f->{
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit)){
                assertEquals(6,input.profiles().levelZeroProfiles().size());
                try(var engine=engine(input)){
                    var vertices=(List<?>)ManagedAEndpointPhysicalOracleTest.field(engine,"vertices");var root=vertices.get(0);
                    Object repeated=null;
                    for(Object v:vertices)if((int)ManagedAEndpointPhysicalOracleTest.value(v,"row")==2
                        &&(double)ManagedAEndpointPhysicalOracleTest.value(v,"offset")==0.0){repeated=v;break;}
                    assertNull(repeated,"Unchanged native phase/state reduction supplies no row2 zero alias; do not add one for this test");
                    var p=ManagedAEndpointPhysicalOracleTest.raster(root);
                    var e=(MetricPoint)ManagedAEndpointPhysicalOracleTest.value(root,"metric");
                    assertNotEquals(e,input.context().metricAt(p),"Captured E must not be replaced with F(P)");
                    var authority=input.managedAEndpoints().orElseThrow();
                    for(var arc:ManagedAEndpointPhysicalOracleTest.arcs(engine)){
                        Object left=vertices.get(arc.fromId()),right=vertices.get(arc.toId());
                        var metricA=expectedMetric(input,authority,left);var metricB=expectedMetric(input,authority,right);
                        assertEquals(metricA,ManagedAEndpointPhysicalOracleTest.value(left,"metric"));
                        assertEquals(metricB,ManagedAEndpointPhysicalOracleTest.value(right,"metric"));
                        assertEquals(metricA.distanceTo(metricB)==0.0,arc.zeroPhysical(),"Actual physical zero classification must precede caches");
                    }
                }
            }
        });
    }
    static MetricPoint expectedMetric(SampledEmissionInput input,ManagedAEndpointAuthority authority,Object vertex)throws Exception{
        int row=(int)ManagedAEndpointPhysicalOracleTest.value(vertex,"row");var endpoint=authority.at(row);
        return endpoint==null?input.context().metricAt(ManagedAEndpointPhysicalOracleTest.raster(vertex)):endpoint.exactMetric();
    }
    @Test void fixedNativeGapCannotBorrowPhysicalCoreOwnership()throws Exception{
        var projection=Projections.getProjectionByCode("EPSG:3857");var base=projection.latlon2eastNorth(new LatLon(49.44,14));
        var coordinates=List.of(base,new EastNorth(base.east()+9.25,base.north()+2.75),new EastNorth(base.east()+20.75,base.north()+8.75))
            .stream().map(projection::eastNorth2latlon).toList();
        var fixture=new ManagedAEndpointProducerTest();fixture.cache=cache;
        fixture.withGeographic(false,coordinates,10,"gap",CancellationProbe.NONE,f->{
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit);var engine=engine(input)){
                var vertices=(List<?>)ManagedAEndpointPhysicalOracleTest.field(engine,"vertices");
                var from=(RowState)ManagedAEndpointPhysicalOracleTest.value(vertices.get(0),"endpoint");
                var to=(RowState)ManagedAEndpointPhysicalOracleTest.value(vertices.get(vertices.size()-1),"endpoint");
                var owners=(NativeAEmissionSolver.NativeEndpointInventory)ManagedAEndpointPhysicalOracleTest.field(engine,"endpointOwners");
                assertTrue(owners.ownerFor(from).directCores().isEmpty());
                var evaluator=(NativeConnectorEvaluator)ManagedAEndpointPhysicalOracleTest.field(engine,"evaluator");
                var result=evaluator.evaluate(from,to);
                assertEquals(NativeConnectorEvaluator.Status.UNAVAILABLE,result.status());
                assertEquals(NativeConnectorEvaluator.Reason.ENDPOINT_OWNERSHIP,result.reason());
            }
        });
    }
    static AutoCloseable engine(SampledEmissionInput input)throws Exception{
        var authority=input.managedAEndpoints().orElseThrow();double norm=input.context().trackerNormalizationRasterPx();
        var nativeTracking=new CorridorAwareTracker().trackDetailed(input.profiles(),norm,authority.junctions());
        var track=nativeTracking.tracks().stream().filter(t->!t.parent()).findFirst().orElseThrow();
        var type=Class.forName(NativeAEmissionSolver.class.getName()+"$Engine");
        var constructor=Arrays.stream(type.getDeclaredConstructors()).filter(c->c.getParameterCount()==15).findFirst().orElseThrow();constructor.setAccessible(true);
        var owner=input.openExecutionOwner("actual-native-degeneracy-test");input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(2048);
        try{return (AutoCloseable)constructor.newInstance(new CorridorCenterlineOptimizer(),input,track,nativeTracking.profiles(),norm,authority.junctions(),nativeTracking.scaleEvidence(),List.of(),
            ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION,GeometryCleanupConfig.disabled(),owner,null,null,NativeAEmissionSolver.ExecutionChoice.UNCHANGED_V1);
        }catch(Exception failure){owner.close();throw failure;}
    }
}
