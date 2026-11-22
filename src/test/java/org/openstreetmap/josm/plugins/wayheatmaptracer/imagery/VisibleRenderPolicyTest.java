package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.imagery.OffsetBookmark;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.layer.imagery.*;
import org.openstreetmap.josm.spi.preferences.*;

/** Real public host policy mutations; no synthetic policy or source receipt grants authority. */
class VisibleRenderPolicyTest {
    @TempDir java.nio.file.Path directory;
    private void withLayer(Consumer<ManagedHeatmapLayer> action) throws Exception {
        withLayer(action,true);
    }
    private void withLayer(Consumer<ManagedHeatmapLayer> action,boolean destroyAtEnd) throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories(){
            public java.io.File getPreferencesDirectory(boolean create){return directory.toFile();}
            public java.io.File getUserDataDirectory(boolean create){return directory.toFile();}
            public java.io.File getCacheDirectory(boolean create){return directory.toFile();}
        });
        SwingUtilities.invokeAndWait(()->{
            ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
            var layer=new ManagedHeatmapLayer(new ImageryInfo("public-policy","https://example.invalid/{zoom}/{x}/{y}.png","tms"));
            try{layer.getDisplaySettings().setAutoLoad(false);layer.getDisplaySettings().setAutoZoom(false);layer.setZoomLevel(15);action.accept(layer);}
            finally{if(destroyAtEnd)layer.destroy();}
        });
    }
    @Test void directStandardSettersAreDetectedWithoutAnEpochCallback() throws Exception {
        for(Consumer<ManagedHeatmapLayer> mutation:List.<Consumer<ManagedHeatmapLayer>>of(
                layer->layer.getImageProcessors().stream().filter(p->p.getClass()==GammaImageProcessor.class).map(GammaImageProcessor.class::cast).findFirst().orElseThrow().setGamma(1.2),
                layer->layer.getImageProcessors().stream().filter(p->p.getClass()==ColorfulImageProcessor.class).map(ColorfulImageProcessor.class::cast).findFirst().orElseThrow().setColorfulness(.7),
                layer->layer.getImageProcessors().stream().filter(p->p.getClass()==SharpenImageProcessor.class).map(SharpenImageProcessor.class::cast).findFirst().orElseThrow().setSharpenLevel(.5f)))withLayer(layer->{
            var policy=VisibleRenderPolicy.capture(layer);var before=layer.sourceEpoch().captureStable();mutation.accept(layer);
            assertDoesNotThrow(()->layer.sourceEpoch().requireCurrent(before),"Direct public processor setters do not publish the host filter callback");
            assertThrows(IllegalStateException.class,policy::requireCurrent);
        });
    }
    @Test void actualProcessorIdentityOrderAndExactClassesAreRequired() throws Exception {
        withLayer(layer->{var policy=VisibleRenderPolicy.capture(layer);assertTrue(layer.getImageProcessors().size()>=2);java.util.Collections.swap(layer.getImageProcessors(),0,1);assertThrows(IllegalStateException.class,policy::requireCurrent);});
        withLayer(layer->{var policy=VisibleRenderPolicy.capture(layer);layer.addImageProcessor(new GammaImageProcessor());assertThrows(IllegalStateException.class,policy::requireCurrent);});
        withLayer(layer->{var policy=VisibleRenderPolicy.capture(layer);layer.getImageProcessors().remove(0);assertThrows(IllegalStateException.class,policy::requireCurrent);});
        withLayer(layer->{layer.addImageProcessor(image->image);assertThrows(UnsupportedOperationException.class,()->VisibleRenderPolicy.capture(layer));});
        withLayer(layer->{layer.addImageProcessor(new GammaImageProcessor(){});assertThrows(UnsupportedOperationException.class,()->VisibleRenderPolicy.capture(layer));});
    }
    @Test void effectiveDisplayStateAndSourceDestructionInvalidatePolicy() throws Exception {
        for(Consumer<ManagedHeatmapLayer> mutation:List.<Consumer<ManagedHeatmapLayer>>of(
                layer->layer.setOpacity(.5),layer->layer.setVisible(false),ManagedHeatmapLayer::filterChanged,
                layer->layer.getDisplaySettings().setAutoLoad(true),layer->layer.getDisplaySettings().setShowErrors(!layer.getDisplaySettings().isShowErrors()),
                layer->layer.getDisplaySettings().setOffsetBookmark(new OffsetBookmark("EPSG:3857","public-id","public-policy","offset",1,2))))withLayer(layer->{var policy=VisibleRenderPolicy.capture(layer);mutation.accept(layer);assertThrows(IllegalStateException.class,policy::requireCurrent);});
        withLayer(layer->{var policy=VisibleRenderPolicy.capture(layer);layer.destroy();assertThrows(IllegalStateException.class,policy::requireCurrent);},false);
    }
    @Test void manualZoomIsMaterialWhileAutomaticViewportZoomIsNot() throws Exception {
        withLayer(layer->{var policy=VisibleRenderPolicy.capture(layer);layer.setZoomLevel(16);assertThrows(IllegalStateException.class,policy::requireCurrent);});
        withLayer(layer->{layer.getDisplaySettings().setAutoZoom(true);var policy=VisibleRenderPolicy.capture(layer);layer.setZoomLevel(16);assertDoesNotThrow(policy::requireCurrent);});
    }
}
