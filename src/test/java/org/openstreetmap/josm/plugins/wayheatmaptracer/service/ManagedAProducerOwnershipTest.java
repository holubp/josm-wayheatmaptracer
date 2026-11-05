package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;

class ManagedAProducerOwnershipTest {
    @TempDir Path cache;
    @Test void endpointProtectionDoesNotInventASharedWayJunctionFlag()throws Exception{
        var fixture=new ManagedAEndpointProducerTest();fixture.cache=cache;
        fixture.withCapture(false,f->{try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit)){
            long bytes=f.ledger().currentBytes();
            assertThrows(IllegalStateException.class,()->ManagedAEndpointProducerTest.produce(f,permit));
            assertFalse(permit.retainedOwner().isClosed());input.requireOpenAndUnchanged();assertEquals(bytes,f.ledger().currentBytes());
            for(var endpoint:input.managedAEndpoints().orElseThrow().junctions().constraints()){
                assertTrue(endpoint.fixed());assertFalse(endpoint.junction(),"Each actual endpoint belongs to only the selected Way");
            }
        }});
    }
    @Test void equalActualPayloadCannotForgeAnotherPermitsNativeProducerReceipt()throws Exception{
        var fixture=new ManagedAEndpointProducerTest();fixture.cache=cache;
        fixture.withCapture(false,f->{
            try(var first=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,first);
                var second=ManagedAEndpointProducerTest.bind(f)){
                var actual=(RetainedManagedScalarProfiles)ManagedAEndpointPhysicalOracleTest.field(input.context(),"managed");
                second.beginProduction();
                try(var fakeOwner=second.retainedOwner().child("unissued-test-payload")){
                    fakeOwner.reserve(1024);
                    // Genuine data, but no producer ran for this permit. No private guard is weakened/reset.
                    var unissued=new RetainedManagedScalarProfiles(f.capture().selectedMosaic(),actual.nativeGrid(),actual.profileSet(),fakeOwner,
                        f.capture(),actual.sourceParameters().orElseThrow(),actual.groundTransform().orElseThrow(),second.retainedOwner(),f.budget());
                    var published=new java.util.concurrent.atomic.AtomicReference<SampledEmissionInput>();
                    try{assertThrows(IllegalArgumentException.class,()->published.set(OptionalEmissionContextFactory.fromManagedCapture(second,unissued)));}
                    finally{if(published.get()!=null)published.get().close();}
                }
            }
        });
    }
}
