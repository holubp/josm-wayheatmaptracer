package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.spi.preferences.*;

/** Actual synthetic managed transport, captured fixed selection and unchanged native producer oracle. */
class ManagedAEndpointProducerTest {
    @TempDir Path cache;
    @Test void retainedAProducerKeepsActualNativeMultilevelProfilesAndFloatQ() throws Exception {
        withCapture(false,f->{
            MultiScaleProfileSet expected=f.sampler.sampleMultiScaleProfiles(f.capture.selectedMosaic(),f.source,"hot",f.settings);
            try(var permit=bind(f)){
                long reads=f.budget.operationUnits(EmissionWorkBudget.Operation.NODE_READ);
                assertThrows(IllegalArgumentException.class,()->f.sampler.sampleProfilesForEmission(permit,CancellationProbe.NONE));
                assertEquals(reads,f.budget.operationUnits(EmissionWorkBudget.Operation.NODE_READ),"Wrong entry must not execute a Legacy sampler");
            try(var input=produce(f,permit)) {
                assertTrue(input.legacyBinding().isEmpty());
                var actual=input.profiles();assertEquals(expected.levels().size(),actual.levels().size());assertTrue(actual.levels().size()>1);
                for(int l=0;l<expected.levels().size();l++) {
                    var a=expected.levels().get(l);var b=actual.levels().get(l);
                    assertEquals(a.level(),b.level());assertEquals(a.reduction(),b.reduction());assertEquals(a.effectiveSigmaL0(),b.effectiveSigmaL0(),0);
                    assertEquals(a.profiles().size(),b.profiles().size());
                    for(int r=0;r<a.profiles().size();r++) {
                        var x=a.profiles().get(r);var y=b.profiles().get(r);
                        assertEquals(x.samplingAnchor(),y.samplingAnchor());assertEquals(x.normalScreen(),y.normalScreen());
                        assertEquals(x.intensitySamples(),y.intensitySamples());assertEquals(x.peaks(),y.peaks());
                        assertEquals(x.anchorWithinRaster(),y.anchorWithinRaster());
                        for(double offset:List.of(-6.0,0.0,6.0))assertEquals(x.projectedLateralTransform().orElseThrow().atOffset(offset),y.projectedLateralTransform().orElseThrow().atOffset(offset));
                    }
                }
                var grid=input.context().nativeGrid();double v=Double.NaN;
                for(int y=0;y<grid.height()&&!Double.isFinite(v);y++)for(int x=0;x<grid.width();x++)if(Double.isFinite(grid.nodeValue(x,y))){v=grid.nodeValue(x,y);break;}
                assertTrue(Double.isFinite(v));assertEquals((double)(float)v,v,0);
                assertEquals(f.capture.trackerNormalizationRasterPx(),input.context().trackerNormalizationRasterPx(),0);
                assertTrue(grid.originX()>0||grid.originY()>0,"Actual source crop is distinct from the complete mosaic grid");
                var raw=ManagedAFloatGridView.class.getDeclaredField("field");raw.setAccessible(true);
                var field=(ScalarIntensityField)raw.get(grid);
                assertTrue(Double.isFinite(field.sample(field.originX()+field.width()-1,field.originY()+field.height()/2)),
                    "Unchanged native nearest-edge fallback remains observable");
                assertFalse(grid.supportsCell(grid.width()-1,grid.height()/2),"Q cannot borrow native fallback as a complete cell");
                assertFalse(input.context().hasManagedNativeCellPullback());
                assertThrows(IllegalArgumentException.class,()->NativeConnectorEvaluator.openAffinityProfiles(input));
                assertThrows(IllegalArgumentException.class,()->input.context().nativeSegmentContinuousMetricBounds(new RasterPoint(0,0),new RasterPoint(1,1),0,1,input.workBudget()));
                assertNotEquals(input.profiles().levelZeroProfiles().get(input.profiles().levelZeroProfiles().size()-1).cumulativeGroundDistanceMeters(),
                    input.context().sourceChainageMeters(input.profiles().levelZeroProfiles().size()-1),"Native chord chainage is distinct from original source S");
            }
            }
        });
    }
    @Test void capturedInteriorProtectionCannotBecomeEndpointOrNearestRowAuthority() throws Exception {
        withCapture(true,f->assertThrows(IllegalArgumentException.class,()->bind(f)));
    }
    @Test void activeProjectionChangeRefusesBeforeNativeProductionWithoutChangingCapturedAuthority()throws Exception{
        withCapture(false,f->{
            long bytes=f.ledger().currentBytes(),reads=f.budget().operationUnits(EmissionWorkBudget.Operation.NODE_READ);
            var captured=ProjectionRegistry.getProjection();ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
            try{
                assertThrows(IllegalStateException.class,()->bind(f));
                assertEquals(bytes,f.ledger().currentBytes());
                assertEquals(reads,f.budget().operationUnits(EmissionWorkBudget.Operation.NODE_READ));
            }finally{ProjectionRegistry.setProjection(captured);}
        });
    }
    @Test void practicalCapturedEndpointsUseActualNativeStatesAndExactPhysicalMetric() throws Exception {
        withCapture(false,true,.25,CancellationProbe.NONE,f->{try(var permit=bind(f);var input=produce(f,permit)){
            var method=CorridorAwareTracker.class.getMethod("trackManagedAEndpointEmission",SampledEmissionInput.class,
                NativeConnectorEvaluator.QueryPolicy.class,NativeConnectorEvaluator.ExecutionPolicy.class);
            try(var result=(CorridorAwareTracker.OptionalEmissionTracking)method.invoke(new CorridorAwareTracker(),input,
                    NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1,NativeConnectorEvaluator.ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)){
                assertFalse(result.results().isEmpty(),"Actual native stripe must supply a track");
                boolean roundtripDiffers=false;
                for(var track:result.results()){
                    assertFalse(track.emitted().isEmpty());
                    var first=track.emitted().get(0);var last=track.emitted().get(track.emitted().size()-1);
                    assertEquals(0,first.row());assertEquals(input.profiles().levelZeroProfiles().size()-1,last.row());
                    assertEquals(0.0,first.offsetPx(),0);assertEquals(0.0,last.offsetPx(),0);
                    for(var end:List.of(first,last)){
                        var node=f.nodes.get(end==first?0:f.nodes.size()-1);
                        MetricPoint exact=input.context().coordinateFrame().toMetric(new GeographicPoint(node.lat(),node.lon()));
                        assertEquals(exact,end.metric());
                        var profile=input.profiles().levelZeroProfiles().get(end.row());
                        assertEquals(profile.samplingAnchor().rasterPoint(),end.raster());
                        roundtripDiffers|=!input.context().metricAt(end.raster()).equals(exact);
                    }
                    for(var fixed:track.managedFixedEndpoints())assertEquals(f.nodes.get(fixed.row()==0?0:f.nodes.size()-1).getCoor(),fixed.exactGeographic());
                }
                assertTrue(roundtripDiffers,"Actual endpoint F(P) differs from the captured exact metric; enclosure must not masquerade as equality");
            }
        }});
    }
    static OptionalEmissionContextFactory.ManagedCaptureBinding bind(F f)throws Exception {
        return bind(f,()->f.settings);
    }
    static OptionalEmissionContextFactory.ManagedCaptureBinding bind(F f,java.util.function.Supplier<ManagedHeatmapConfig> current)throws Exception {
        try{return (OptionalEmissionContextFactory.ManagedCaptureBinding)OptionalEmissionContextFactory.class
            .getMethod("bindManagedACapture",TileHeatmapSampler.PreparedManagedCapture.class,java.util.function.Supplier.class,EmissionWorkBudget.class,AttemptMemoryLedger.Owner.class)
            .invoke(null,f.capture,current,f.budget,f.owner);
        }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    static SampledEmissionInput produce(F f,OptionalEmissionContextFactory.ManagedCaptureBinding p)throws Exception {
        try{return (SampledEmissionInput)TileHeatmapSampler.class.getMethod("sampleManagedAProfilesForEmission",OptionalEmissionContextFactory.ManagedCaptureBinding.class,CancellationProbe.class)
            .invoke(f.sampler,p,CancellationProbe.NONE);
        }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    record F(TileHeatmapSampler sampler,TileHeatmapSampler.PreparedManagedCapture capture,List<EastNorth> source,
        ManagedHeatmapConfig settings,EmissionWorkBudget budget,AttemptMemoryLedger.Owner owner,AttemptMemoryLedger ledger,List<Node> nodes){}
    interface Check{void run(F f)throws Exception;}
    void withCapture(boolean interior,Check check)throws Exception {
        withCapture(interior,false,check);
    }
    void withCapture(boolean interior,boolean ridge,Check check)throws Exception {
        withCapture(interior,ridge,1,CancellationProbe.NONE,check);
    }
    void withCapture(boolean interior,boolean ridge,double scale,CancellationProbe cancellation,Check check)throws Exception {
        var projection=Projections.getProjectionByCode("EPSG:3857");
        var base=projection.latlon2eastNorth(new LatLon(49.44,14));
        var points=List.of(base,new EastNorth(base.east()+37*scale,base.north()+11*scale),new EastNorth(base.east()+83*scale,base.north()+35*scale));
        withGeographic(interior,points.stream().map(projection::eastNorth2latlon).toList(),10,ridge?"ridge":"flat",cancellation,check);
    }
    void withGeographic(boolean interior,List<LatLon> coordinates,double step,String signal,CancellationProbe cancellation,Check check)throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());Config.setBaseDirectoriesProvider(new IBaseDirectories(){
            public java.io.File getPreferencesDirectory(boolean c){return cache.toFile();}public java.io.File getUserDataDirectory(boolean c){return cache.toFile();}public java.io.File getCacheDirectory(boolean c){return cache.toFile();}});
        var projection=Projections.getProjectionByCode("EPSG:3857");ProjectionRegistry.setProjection(projection);
        var nodes=coordinates.stream().map(Node::new).toList();var data=new DataSet();nodes.forEach(data::addPrimitive);
        if(interior)nodes.get(1).put("name","synthetic protected");var way=new Way();way.setNodes(nodes);data.addPrimitive(way);data.setSelected(way);
        var layer=new OsmDataLayer(data,"managed A synthetic",null);MainApplication.getLayerManager().addLayer(layer);
        var source=nodes.stream().map(n->n.getEastNorth(projection)).toList();
        var config=new ManagedHeatmapConfig("key","policy","signature","token","all","hot","",".*",AlignmentMode.MOVE_EXISTING_NODES,TrackerMode.CORRIDOR_AWARE,
            false,false,false,false,false,false,true,false,false,false,18,4,3,InferenceMode.STABLE_FIXED_SCALE,15,15,28,step,IntensitySamplingMode.COLOR_MAPPING,0);
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0xffa0a0a0);
        var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);var decoder=new TileDecoderClassifier();var ledger=AttemptMemoryLedger.production();
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),cancellation);
        try(var owner=ledger.rootOwner();var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",signal.equals("flat")?bytes.toByteArray():signalTile(r.address(),nodes,signal),null,Duration.ZERO,""),new ManagedTileCache(cache,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var sampler=new TileHeatmapSampler(coordinator);var selection=SelectionResolver.resolve(data,false);
            var witness=ManagedSelectionWitness.capture(data,selection,projection,source,budget,owner);
            try(var capture=sampler.prepareForEmission(config,witness,List.of("hot"),"hot",false,budget,owner)){check.run(new F(sampler,capture,source,config,budget,owner,ledger,nodes));}
        }finally{MainApplication.getLayerManager().removeLayer(layer);}
        assertEquals(0,ledger.currentBytes());
    }
    static byte[] ridgeTile(ManagedTileAddress address,List<Node> nodes) {
        return signalTile(address,nodes,"ridge");
    }
    static byte[] signalTile(ManagedTileAddress address,List<Node> nodes,String signal) {
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);double size=512.0*(1L<<address.zoom());
        var points=nodes.stream().map(n->{double s=Math.sin(Math.toRadians(n.lat()));return new double[]{(n.lon()+180)/360*size,(.5-Math.log((1+s)/(1-s))/(4*Math.PI))*size};}).toList();
        for(int y=0;y<512;y++)for(int x=0;x<512;x++){
            double wx=address.x()*512.0+x+.5,wy=address.y()*512.0+y+.5,d=Double.POSITIVE_INFINITY;
            for(int i=1;i<points.size();i++){var a=points.get(i-1);var b=points.get(i);d=Math.min(d,java.awt.geom.Line2D.ptSegDist(a[0],a[1],b[0],b[1],wx,wy));}
            if(signal.equals("blob"))d=Math.hypot(wx-points.get(0)[0],wy-points.get(0)[1]);
            // Decoder-only texture is >80 native pixels from every source segment; sampling/crop/halo/query stay inside40.
            double gain=1;
            if(signal.equals("gap")){var a=points.get(0);var b=points.get(1);double length=Math.hypot(b[0]-a[0],b[1]-a[1]);double along=((wx-a[0])*(b[0]-a[0])+(wy-a[1])*(b[1]-a[1]))/length;gain=Math.max(0,Math.min(1,along-2));}
            int value=d>80?80+(x*17+y*31)%80:(int)Math.round(gain*240*Math.exp(-.5*Math.pow(d/(signal.equals("blob")?1:2),2)));
            image.setRGB(x,y,0xff000000|value<<16|value<<8|value);
        }
        var bytes=new ByteArrayOutputStream();try{javax.imageio.ImageIO.write(image,"png",bytes);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return bytes.toByteArray();
    }
}
