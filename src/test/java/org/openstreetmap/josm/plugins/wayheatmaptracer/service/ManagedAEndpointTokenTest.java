package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ManagedAEndpointTokenTest {
    @TempDir Path cache;
    @Test void directAndPreparedScalarBindExactOwnedPhysicalPairsAndRejectForgedFacts()throws Exception{
        var fixture=new ManagedAEndpointProducerTest();fixture.cache=cache;
        fixture.withCapture(false,true,.25,CancellationProbe.NONE,f->{
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit);
                var tracking=new CorridorAwareTracker().trackManagedAEndpointEmission(input,ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION)){
                var result=tracking.results().get(0);Object engine=ManagedAEndpointPhysicalOracleTest.field(result,"engine");
                var evaluator=(NativeConnectorEvaluator)ManagedAEndpointPhysicalOracleTest.field(engine,"evaluator");
                var owners=(NativeAEmissionSolver.NativeEndpointInventory)ManagedAEndpointPhysicalOracleTest.field(engine,"endpointOwners");
                var vertices=(List<?>)ManagedAEndpointPhysicalOracleTest.field(engine,"vertices");
                var from=(RowState)ManagedAEndpointPhysicalOracleTest.value(vertices.get(0),"endpoint");
                var to=(RowState)ManagedAEndpointPhysicalOracleTest.value(vertices.get(vertices.size()-1),"endpoint");
                var arcs=ManagedAEndpointPhysicalOracleTest.arcs(engine);var arc=ManagedAEndpointPhysicalOracleTest.arc(arcs,0,vertices.size()-1);
                var cache=evaluator.openNativeScalarPreparations((TransitionProvider)engine,arcs.size());
                var token=cache.prepare(arc,from,to);assertNotNull(token);
                var endpoints=input.managedAEndpoints().orElseThrow().endpoints();
                assertEquals(endpoints.get(0).evidenceRaster(),ManagedAEndpointPhysicalOracleTest.field(token,"a"));
                assertEquals(endpoints.get(1).evidenceRaster(),ManagedAEndpointPhysicalOracleTest.field(token,"b"));
                assertEquals(endpoints.get(0).exactMetric(),ManagedAEndpointPhysicalOracleTest.field(token,"ma"));
                assertEquals(endpoints.get(1).exactMetric(),ManagedAEndpointPhysicalOracleTest.field(token,"mb"));
                var direct=evaluator.evaluate(from,to);var prepared=evaluator.evaluateWithNativeScalar(from,to,token);
                assertEquals(NativeConnectorEvaluator.Status.ADMITTED,direct.status());assertEquals(direct.status(),prepared.status());assertEquals(direct.scalar(),prepared.scalar());
                assertEquals(endpoints.get(0).exactMetric().distanceTo(endpoints.get(1).exactMetric()),direct.emittedLengthMeters(),0);
                assertEquals(endpoints.get(0).evidenceRaster(),direct.probes().get(0).rasterPosition());
                assertEquals(endpoints.get(1).evidenceRaster(),direct.probes().get(direct.probes().size()-1).rasterPosition());
                double expected=(input.context().sourceChainageMeters(to.row())-input.context().sourceChainageMeters(from.row()))/input.context().nativePitchMeters().orElseThrow()
                    *(1-ManagedAEndpointPhysicalOracleTest.mean(input.context().nativeGrid(),endpoints.get(0).evidenceRaster(),endpoints.get(1).evidenceRaster()));
                assertEquals(expected,token.nativeQ(),1e-12);
                var equal=new RowState(from.row(),from.nativeStateId(),from.offsetRasterPixels(),from.sourceClass(),null);
                assertEquals(from,equal);assertNull(owners.ownerFor(equal));
                assertThrows(InvalidCertificateException.class,()->cache.prepare(arc,equal,to));
                int changed=-1;
                for(int i=0;i<2;i++)if(!input.context().metricAt(endpoints.get(i).nativeRaster()).equals(endpoints.get(i).exactMetric())){changed=i;break;}
                assertTrue(changed>=0);var metric=token.getClass().getDeclaredField(changed==0?"ma":"mb");metric.setAccessible(true);Object actual=metric.get(token);
                metric.set(token,input.context().metricAt(endpoints.get(changed).nativeRaster()));
                assertThrows(InvalidCertificateException.class,()->token.requireFor((TransitionProvider)engine,arc));metric.set(token,actual);
                var endpoint=endpoints.get(0);var alias=endpoint.getClass().getDeclaredField("alias");alias.setAccessible(true);
                var actualAlias=(SourceParameterCertificate.SourceAlias)alias.get(endpoint);
                alias.set(endpoint,new SourceParameterCertificate.SourceAlias(actualAlias.row(),actualAlias.originalVertex(),actualAlias.rawFraction(),actualAlias.parameterErrorUpper(),actualAlias.projectedErrorUpper(),actualAlias.metricErrorUpperMeters()));
                assertThrows(IllegalStateException.class,input::requireOpenAndUnchanged);
                assertThrows(IllegalStateException.class,()->token.requireFor((TransitionProvider)engine,arc));alias.set(endpoint,actualAlias);
                token.requireFor((TransitionProvider)engine,arc);assertSame(token,cache.prepare(arc,from,to));
                result.close();assertThrows(IllegalStateException.class,token::scalar);assertNull(owners.ownerFor(from));
            }
        });
    }
    @Test void logicalInputClosureRevokesTokensButRetainsChargedPayloadUntilResultClose()throws Exception{
        var fixture=new ManagedAEndpointProducerTest();fixture.cache=cache;
        fixture.withCapture(false,true,.25,CancellationProbe.NONE,f->{
            long captureBytes=f.ledger().currentBytes();
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit)){
                var tracking=new CorridorAwareTracker().trackManagedAEndpointEmission(input,ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION);
                try{
                    var result=tracking.results().get(0);Object engine=ManagedAEndpointPhysicalOracleTest.field(result,"engine");
                    var evaluator=(NativeConnectorEvaluator)ManagedAEndpointPhysicalOracleTest.field(engine,"evaluator");
                    var vertices=(List<?>)ManagedAEndpointPhysicalOracleTest.field(engine,"vertices");var arcs=ManagedAEndpointPhysicalOracleTest.arcs(engine);
                    var a=(RowState)ManagedAEndpointPhysicalOracleTest.value(vertices.get(0),"endpoint");var b=(RowState)ManagedAEndpointPhysicalOracleTest.value(vertices.get(vertices.size()-1),"endpoint");
                    var token=evaluator.openNativeScalarPreparations((TransitionProvider)engine,arcs.size()).prepare(ManagedAEndpointPhysicalOracleTest.arc(arcs,0,vertices.size()-1),a,b);
                    var endpoint=input.managedAEndpoints().orElseThrow().endpoints().get(0);input.close();
                    assertTrue(f.ledger().currentBytes()>captureBytes);
                    assertThrows(IllegalStateException.class,result::emitted);assertThrows(IllegalStateException.class,endpoint::exactMetric);
                    assertThrows(IllegalStateException.class,token::scalar);
                }finally{tracking.close();}
            }
            assertEquals(captureBytes,f.ledger().currentBytes());
        });
    }
}
