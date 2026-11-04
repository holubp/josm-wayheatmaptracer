package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

class NativeMeasuredManagedProfileTest {
    @TempDir Path cache;
    @Test void actualManagedModePointAndCoreBridgeRetainAuthoritativeNativeBits() throws Exception {
        withManaged(new AtomicBoolean(),f->{
            try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input);var domainOwner=f.input.openExecutionOwner("m1-real-domain-test")){
                var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),domainOwner);
                LegacyEmissionDomain.State state=null;
                for(int row=1;row<domain.rowCount()-1&&state==null;row++)for(var candidate:domain.observedAt(row)){
                    var p=domain.rasterPoint(candidate);
                    if(!p.equals(f.input.context().rasterAt(f.input.context().metricAt(p)))){state=candidate;break;}
                }
                assertNotNull(state,"Adversarial actual native-state round trip must differ; a proximity equality test would be vacuous");
                var p=domain.rasterPoint(state);var metric=f.input.context().metricAt(p);
                var profile=session.metricAnchoredQuery(state.evaluatorState().row(),p,new MetricPoint(0,1));
                assertEquals(Optional.of(p),profile.authoritativeAnchor());assertFalse(profile.modes().isEmpty());
                var observation=session.observePoint(profile,p);
                assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,observation.availability());
                assertEquals(p,observation.actualRaster());assertEquals(metric,observation.actualMetric());
                assertNotEquals(p,observation.inverseZeroRaster());assertTrue(observation.bridge().isPresent());
                assertTrue(observation.componentBridge().isPresent());
                assertTrue(observation.bridge().orElseThrow().minimum().orElseThrow()>=observation.component().orElseThrow().accepted().rawCore().orElseThrow().threshold());
                var binding=session.bindNativeState(profile,domain,state);assertSame(state,binding.state());binding.validate();
                boolean completeSupport=false;
                for(var mode:profile.modes()){
                    double c=mode.accepted().mode().centerMeters();
                    assertEquals(new MetricPoint(metric.xMeters()+c*0,metric.yMeters()+c),mode.metricPosition());
                    assertEquals(f.input.context().rasterAt(mode.metricPosition()),mode.rasterPosition());
                    assertEquals(f.input.context().metricAt(mode.rasterPosition()),mode.forwardMetricPosition());
                    assertEquals(f.input.context().nativeGrid().toGrid(mode.rasterPosition()),mode.nativeGridPosition());
                    if(mode.support().availability()==NativeMeasuredProfileReceipt.Availability.COMPLETE){
                        completeSupport=true;assertTrue(mode.support().routeProved());assertTrue(mode.support().acquiredBoxProved());
                        assertTrue(mode.support().presentCellVisits().orElseThrow()>0);assertEquals(0,mode.support().invalidCellVisits().orElseThrow());
                    }
                }
                assertTrue(completeSupport,"M1 must issue useful actual managed proof footprints, not blanket unavailable");
                var family=session.freezeFamily(metric,new MetricPoint(3,0),new MetricPoint(0,2),-.25,.25);
                assertTrue(family.continuousTransportCertified());
                var dual=family.project(new MetricPoint(metric.xMeters()+1.5,metric.yMeters()+4));
                assertTrue(dual.minimumS()<=.5&&dual.maximumS()>=.5);assertTrue(dual.minimumU()<=2&&dual.maximumU()>=2);
            }
        });
    }
    @Test void cancellationStaysTerminalEvenIfProbeWouldBeCached() throws Exception {
        var cancelled=new AtomicBoolean();
        withManaged(cancelled,f->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input)){
            var p=f.input.context().observations().get(1).location().rasterPoint();
            var profile=session.metricAnchoredQuery(1,p,new MetricPoint(0,1));cancelled.set(true);
            var first=assertThrows(CancellationException.class,profile::validate);cancelled.set(false);
            assertSame(first,assertThrows(CancellationException.class,()->session.metricAnchoredQuery(1,p,new MetricPoint(0,1))));
        }});
    }
    @Test void outsideFrameSamplesRemainActualUnqueriedSlotsInAPartialProfile() throws Exception {
        withManaged(new AtomicBoolean(),f->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input)){
            var p=f.input.context().observations().get(1).location().rasterPoint();
            var profile=session.metricAnchoredQuery(1,p,new MetricPoint(0,1e8));
            assertEquals(profile.probe().samples().size(),profile.queries().size());
            assertTrue(profile.queries().stream().anyMatch(q->!q.scalarQueried()&&q.raster().isEmpty()));
            assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,session.observePoint(profile,p).availability());
        }});
    }
    @Test void memoryAdmissionIsStickyAndPartialSessionOwnershipCloses() throws Exception {
        withManaged(new AtomicBoolean(),f->{try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input)){
            var p=f.input.context().observations().get(1).location().rasterPoint();
            AttemptMemoryLedger.ResourceLimitException first;
            try(var pressure=f.owner.child("synthetic-m1-pressure")){
                pressure.reserve(AttemptMemoryLedger.MAX_BYTES-f.ledger.currentBytes()-4096);
                first=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->session.metricAnchoredQuery(1,p,new MetricPoint(0,1)));
            }
            assertSame(first,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->session.metricAnchoredQuery(1,p,new MetricPoint(0,1))));
            assertEquals(AttemptMemoryLedger.MAX_BYTES,first.limitBytes());
        }});
    }
    private record Fixture(SampledEmissionInput input,AttemptMemoryLedger.Owner owner,AttemptMemoryLedger ledger) { }
    @FunctionalInterface private interface Check { void accept(Fixture fixture) throws Exception; }
    private void withManaged(AtomicBoolean cancelled,Check check) throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories(){
            public java.io.File getPreferencesDirectory(boolean create){return cache.toFile();}
            public java.io.File getUserDataDirectory(boolean create){return cache.toFile();}
            public java.io.File getCacheDirectory(boolean create){return cache.toFile();}
        });
        var projection=Projections.getProjectionByCode("EPSG:3857");ProjectionRegistry.setProjection(projection);
        var midpoint=projection.latlon2eastNorth(new LatLon(49.44,14));
        var source=List.of(new EastNorth(midpoint.east()-40,midpoint.north()),new EastNorth(midpoint.east()+40,midpoint.north()));
        var dataSet=new DataSet();var nodes=source.stream().map(p->new Node(projection.eastNorth2latlon(p))).toList();nodes.forEach(dataSet::addPrimitive);
        var way=new Way();way.setNodes(nodes);dataSet.addPrimitive(way);dataSet.setSelected(way);
        var layer=new OsmDataLayer(dataSet,"synthetic M1 receipt-only source",null);MainApplication.getLayerManager().addLayer(layer);
        var selection=SelectionResolver.resolve(dataSet,false);var projected=nodes.stream().map(n->n.getEastNorth(projection)).toList();
        var settings=new ManagedHeatmapConfig("synthetic","synthetic","synthetic","synthetic","all","hot","",".*",
            AlignmentMode.MOVE_EXISTING_NODES,TrackerMode.LEGACY_V02,false,false,false,false,false,false,true,false,false,false,
            18,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,28,1.56,IntensitySamplingMode.COLOR_MAPPING,0);
        double worldSize=TileHeatmapSampler.TILE_SIZE*Math.scalb(1.0,15),sine=Math.sin(Math.toRadians(49.44));
        double bandY=(.5-Math.log((1+sine)/(1-sine))/(4*Math.PI))*worldSize;
        var ledger=AttemptMemoryLedger.production();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),cancelled::get);
        var decoder=new TileDecoderClassifier();
        try(var owner=ledger.rootOwner();var coordinator=new TileFetchCoordinator((request,credentials)->{
            int size=TileHeatmapSampler.TILE_SIZE;
            var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<size;y++){
                double delta=request.address().y()*size+y+.5-bandY;int v=(int)Math.round(230*Math.exp(-.5*delta*delta/4));
                for(int x=0;x<size;x++)image.setRGB(x,y,(v==0?0:0xFF000000)|(v<<16));
            }
            var out=new ByteArrayOutputStream();try{javax.imageio.ImageIO.write(image,"png",out);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",out.toByteArray(),null,Duration.ZERO,"");
        },new ManagedTileCache(cache,decoder),decoder,TileReliabilityPolicy.defaults())){
            var sampler=new TileHeatmapSampler(coordinator);
            var witness=ManagedSelectionWitness.capture(dataSet,selection,projection,projected,budget,owner);
            try(var capture=sampler.prepareForEmission(settings,witness,List.of("hot"),"hot",false,budget,owner);
                var permit=OptionalEmissionContextFactory.bindManagedCapture(capture,()->settings,budget,owner);
                var input=sampler.sampleProfilesForEmission(permit,CancellationProbe.NONE)){
                check.accept(new Fixture(input,owner,ledger));
            }
        }finally{MainApplication.getLayerManager().removeLayer(layer);}
        assertEquals(0,ledger.currentBytes());
    }
}
