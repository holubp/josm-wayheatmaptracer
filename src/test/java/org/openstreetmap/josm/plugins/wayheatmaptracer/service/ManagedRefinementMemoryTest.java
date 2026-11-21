package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Actual acquisition ownership, independent of the coordinator's separately bounded cache. */
class ManagedRefinementMemoryTest {
    @TempDir java.nio.file.Path temporary;
    private static ManagedModernPreviewSource.Request request() {
        return new ManagedModernPreviewSource.Request(List.of(new GeographicPoint(.01,.01),new GeographicPoint(.01,.0101)),
            "all","hot",15,new ManagedTileGeneration(9),7.01,1.56,"public-memory-scene",List.of("hot","blue","bluered","purple","gray"));
    }
    private static byte[] png() throws Exception {
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0xff000000|((x+y)%200+20)<<16);
        var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",bytes));return bytes.toByteArray();
    }
    @Test void validationWorkspaceLeavesTheUnchangedPeakOnlyAfterAcquisitionReturns() throws Exception {
        var bytes=png();
        for(var zooms:List.of(Set.of(15),Set.of(15,14))) {
            var decoder=new TileDecoderClassifier();var ledger=AttemptMemoryLedger.production();
            var observed=new ArrayList<Long>();var addresses=new ArrayList<ManagedTileAddress>();
            try(var coordinator=new TileFetchCoordinator((r,c)->{
                observed.add(ledger.currentBytes());addresses.add(r.address());
                return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");
            },new ManagedTileCache(temporary.resolve("success-"+zooms.size()),decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
                coordinator.updateActiveGeneration(new ManagedTileGeneration(9));
                var acquired=new ManagedModernPreviewSource(coordinator).acquireRefinementSources(request(),zooms,true,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);
                // Two source coordinates: geometry264192; five512² inference frames42205184.
                long peak=zooms.size()==1?42469376L:84412416L;
                assertEquals(5*zooms.size(),addresses.size());assertTrue(observed.stream().allMatch(value->value==peak));
                assertEquals(peak,ledger.peakBytes());assertEquals(42469376L,ledger.currentBytes(),"completed validation workspace must not become retained pixel ownership");
                var actual=acquired.value();assertTrue(actual.completeAggregate());assertEquals(Set.copyOf(request().requiredPalettes()),actual.palettes());
                for(String palette:request().requiredPalettes()) {
                    assertEquals(zooms,actual.sourceReceipts().get(palette).keySet());
                    assertTrue(actual.sourceReceipts().get(palette).values().stream().allMatch(ManagedModernPreviewSource.ZoomReceipt::complete));
                    var raster=actual.raster(palette,coordinator);assertEquals(512,raster.image().getWidth());assertEquals(512,raster.image().getHeight());
                    assertEquals(0xff610000,raster.image().getRGB(33,44));for(boolean valid:raster.validity())assertTrue(valid);
                }
                acquired.owner().close();assertEquals(0,ledger.currentBytes());
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void unequalZoomFramesReleaseOnlyTheCompletedSecondaryFrameAllowance() throws Exception {
        var original=request();var wide=new ManagedModernPreviewSource.Request(List.of(new GeographicPoint(.01,.01),new GeographicPoint(.01,.0111)),
            original.activity(),original.palette(),original.zoom(),original.generation(),original.searchRadiusMeters(),original.sampleStepMeters(),original.sourceIdentity(),original.requiredPalettes());
        var bytes=png();var decoder=new TileDecoderClassifier();var ledger=AttemptMemoryLedger.production();var count=new AtomicInteger();
        try(var coordinator=new TileFetchCoordinator((r,c)->{count.incrementAndGet();return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},
                new ManagedTileCache(temporary,decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
            coordinator.updateActiveGeneration(wide.generation());
            var acquired=new ManagedModernPreviewSource(coordinator).acquireRefinementSources(wide,Set.of(15,14),true,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);
            assertEquals(15,count.get());assertEquals(126355456L,ledger.peakBytes());assertEquals(84412416L,ledger.currentBytes());
            assertEquals(1024,acquired.value().raster("hot",coordinator).image().getWidth());assertTrue(acquired.value().completeAggregate());
            acquired.owner().close();assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void retainedCapacityAloneCannotBypassTheAllZoomPeakAndFailureRollsBack() throws Exception {
        var count=new AtomicInteger();var decoder=new TileDecoderClassifier();
        // Caller4096 + geometry264192 + unchanged all-zoom peak84148224, minus one byte.
        var ledger=new AttemptMemoryLedger(84416511L);
        try(var coordinator=new TileFetchCoordinator((r,c)->{count.incrementAndGet();throw new AssertionError("peak refusal must precede transport");},
                new ManagedTileCache(temporary,decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(9));owner.reserve(4096).adopt(new Object());
            var failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->new ManagedModernPreviewSource(coordinator)
                .acquireRefinementSources(request(),Set.of(15,14),true,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner));
            assertTrue(failure.requestedBytes()>failure.limitBytes()-failure.currentBytes());
            assertEquals(0,count.get());assertEquals(4096,ledger.currentBytes());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void callerCancellationExceptionAndErrorReleaseBothAdmittedLeases() throws Exception {
        var bytes=png();
        for(String failure:List.of("cancel","exception","error")) {
            var decoder=new TileDecoderClassifier();var count=new AtomicInteger();var ledger=AttemptMemoryLedger.production();
            try(var coordinator=new TileFetchCoordinator((r,c)->{count.incrementAndGet();return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},
                    new ManagedTileCache(temporary.resolve(failure),decoder),decoder,TileReliabilityPolicy.defaults());var owner=ledger.rootOwner()) {
                coordinator.updateActiveGeneration(new ManagedTileGeneration(9));
                CancellationProbe probe=()->{if(count.get()>0){if(failure.equals("error"))throw new AssertionError("synthetic-workspace-error");
                    if(failure.equals("exception"))throw new IllegalStateException("synthetic-workspace-exception");return true;}return false;};
                var source=new ManagedModernPreviewSource(coordinator);
                if(failure.equals("error"))assertThrows(AssertionError.class,()->source.acquireRefinementSources(request(),Set.of(15,14),true,CredentialSnapshot.fromConfig(null),probe,owner));
                else if(failure.equals("cancel"))assertThrows(java.util.concurrent.CancellationException.class,()->source.acquireRefinementSources(request(),Set.of(15,14),true,CredentialSnapshot.fromConfig(null),probe,owner));
                else assertThrows(IllegalStateException.class,()->source.acquireRefinementSources(request(),Set.of(15,14),true,CredentialSnapshot.fromConfig(null),probe,owner));
                assertTrue(count.get()>0);assertEquals(84412416L,ledger.peakBytes());assertEquals(0,ledger.currentBytes());
            }
        }
    }
}
