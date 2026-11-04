package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.spi.preferences.*;

/** Real managed authority and original-row snapshots with explicitly synthetic tiles/selection. */
public final class LegacyCeilingTicketFixture {
    public record Managed(SampledEmissionInput input,AttemptMemoryLedger.Owner owner,AttemptMemoryLedger ledger,
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles,AtomicReference<Runnable> settingsHook) { }
    @FunctionalInterface public interface Check {void run(Managed fixture)throws Exception;}
    public static Map<EmissionWorkBudget.Operation,Long> publicationWork(SampledEmissionInput input,AttemptMemoryLedger.Owner owner) {
        var before=input.workBudget().snapshot();
        try(var publication=input.prepareManagedFinalPublication(owner,64,256)) {
            var paid=input.workBudget().snapshot();publication.validate();assertEquals(paid,input.workBudget().snapshot());
        }
        var delta=new EnumMap<EmissionWorkBudget.Operation,Long>(EmissionWorkBudget.Operation.class);
        for(var op:EmissionWorkBudget.Operation.values())delta.put(op,input.workBudget().operationUnits(op)-before.operationUnits().get(op));
        return delta;
    }
    public static void managed(Path cache,EmissionWorkBudget budget,Check check)throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories(){
            public java.io.File getPreferencesDirectory(boolean c){return cache.toFile();}
            public java.io.File getUserDataDirectory(boolean c){return cache.toFile();}
            public java.io.File getCacheDirectory(boolean c){return cache.toFile();}
        });
        var projection=Projections.getProjectionByCode("EPSG:3857");ProjectionRegistry.setProjection(projection);
        var data=new DataSet();var a=new Node(projection.eastNorth2latlon(new EastNorth(500,500)));
        var b=new Node(projection.eastNorth2latlon(new EastNorth(550,500)));data.addPrimitive(a);data.addPrimitive(b);
        var way=new Way();way.setNodes(List.of(a,b));data.addPrimitive(way);data.setSelected(way);
        var layer=new OsmDataLayer(data,"census ticket synthetic selection",null);MainApplication.getLayerManager().addLayer(layer);
        var ledger=AttemptMemoryLedger.production();var hook=new AtomicReference<Runnable>(()->{});
        var settings=new ManagedHeatmapConfig("key","policy","signature","token","all","hot","",".*",
            AlignmentMode.MOVE_EXISTING_NODES,TrackerMode.LEGACY_V02,false,false,false,false,false,false,true,false,false,false,
            18,4,3,InferenceMode.STABLE_FIXED_SCALE,15,15,28,5,IntensitySamplingMode.COLOR_MAPPING,0);
        var image=new BufferedImage(TileHeatmapSampler.TILE_SIZE,TileHeatmapSampler.TILE_SIZE,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,0xFF808080);
        var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);var bytes=png.toByteArray();var decoder=new TileDecoderClassifier();
        try(var owner=ledger.rootOwner();var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(
                TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(cache,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var sampler=new TileHeatmapSampler(coordinator);var selection=SelectionResolver.resolve(data,false);
            var source=selection.segmentNodes().stream().map(n->n.getEastNorth(projection)).toList();
            var witness=ManagedSelectionWitness.capture(data,selection,projection,source,budget,owner);
            try(var capture=sampler.prepareForEmission(settings,witness,List.of("hot"),"hot",false,budget,owner);
                var permit=OptionalEmissionContextFactory.bindManagedCapture(capture,()->{hook.get().run();return settings;},budget,owner);
                var input=sampler.sampleProfilesForEmission(permit,CancellationProbe.NONE)) {
                var original=input.legacyBinding().orElseThrow().observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
                assertTrue(original.size()>4);check.run(new Managed(input,owner,ledger,original,hook));
            }
        }finally{MainApplication.getLayerManager().removeLayer(layer);}
        assertEquals(0,ledger.currentBytes());
    }
}
