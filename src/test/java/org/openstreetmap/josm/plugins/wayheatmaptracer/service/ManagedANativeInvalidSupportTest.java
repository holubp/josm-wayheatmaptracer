package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ManagedANativeInvalidSupportTest {
    @Test void retainedFloatProducerPreservesNativeInvalidSamplesAndAllLevels(){
        var image=new BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<20;y++)for(int x=0;x<20;x++)image.setRGB(x,y,0xff000000|((x+y)*5)*0x010101);
        var anchors=ProfileSamplingAnchor.rasterOnly(List.of(new Point2D.Double(1,1),new Point2D.Double(18,1)));
        var sampler=new RenderedHeatmapSampler();
        var expected=sampler.sampleMultiScaleProfilesOnAnchors(image,anchors,5,1,"hot",1,1,IntensitySamplingMode.COLOR_MAPPING,1,null);
        var ledger=AttemptMemoryLedger.production();boolean invalid=false;
        try(var owner=ledger.rootOwner()){
            var actual=sampler.sampleManagedARetainingFloat(image,anchors,5,1,"hot",1,1,IntensitySamplingMode.COLOR_MAPPING,null,
                new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE),owner,CancellationProbe.NONE).profiles();
            assertEquals(expected.levels().size(),actual.levels().size());
            for(int i=0;i<expected.levels().size();i++){
                var e=expected.levels().get(i);var a=actual.levels().get(i);assertEquals(e.reduction(),a.reduction());
                for(int r=0;r<e.profiles().size();r++){
                    assertEquals(e.profiles().get(r).intensitySamples(),a.profiles().get(r).intensitySamples());
                    assertEquals(e.profiles().get(r).peaks(),a.profiles().get(r).peaks());
                    invalid|=a.profiles().get(r).intensitySamples().stream().anyMatch(s->!s.insideRaster());
                }
            }
        }
        assertTrue(invalid,"Edge fixture must contain actual invalid native slots");assertEquals(0,ledger.currentBytes());
    }
}
