package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class OriginalInputHashTest {
    static BufferedImage image(int seed){var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,2,2,new int[]{0x01234567^seed,0x89abcdef,0xfedcba98,0x76543210},0,2);return image;}
    static GeometryWorkContext work(AttemptMemoryLedger.Owner owner,long visits,long bytes,CancellationProbe cancel){return new GeometryWorkContext(visits,bytes,System.nanoTime()+60_000_000_000L,cancel,owner);}
    @Test void knownDecodedArgbBytesAndCacheKeepImageIdentitySeparateFromMapping() throws Exception {
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,10000,AttemptMemoryLedger.MAX_BYTES,CancellationProbe.NONE);var input=image(0);
            String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex("0123456789abcdeffedcba9876543210")));
            assertEquals(expected,RasterEvidenceCapture.hashOriginalArgb(input,work));
            var cache=new RasterEvidenceCapture.OriginalInputHashes(work);assertEquals(expected,cache.single(input,input));long used=work.visits();
            assertEquals(expected,cache.single(input,input));assertTrue(work.visits()-used<=2,"A second mapping reuses the original input digest");
            var second=image(0);assertEquals(expected,cache.single(second,second));assertEquals(2,cache.singleImagesHashed(),"Distinct acquired image identities are not cache aliases");
            assertNotEquals(expected,RasterEvidenceCapture.hashOriginalArgb(image(1),work));
            System.out.println("original-argb helper visits="+work.visits()+" peakBytes="+owner.peakBytes());
        }
    }
    @Test void aggregateProtocolBindsEveryNamedOriginalImageInCanonicalOrder() throws Exception {
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.01));
        var transform=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(0,0),1,0,0,1);
        var inputs=new ArrayList<RasterEvidenceCapture.NamedOriginalRaster>();var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.write("g004-original-argb-set-v1\n".getBytes(StandardCharsets.US_ASCII));out.writeInt(5);int seed=0;
        for(String palette:List.of("hot","blue","bluered","purple","gray")) {
            var image=image(seed++);inputs.add(new RasterEvidenceCapture.NamedOriginalRaster(palette,image,transform));var name=palette.getBytes(StandardCharsets.US_ASCII);
            out.writeInt(name.length);out.write(name);out.writeInt(2);out.writeInt(2);for(int y=0;y<2;y++)for(int x=0;x<2;x++)out.writeInt(image.getRGB(x,y));
        }
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,10000,AttemptMemoryLedger.MAX_BYTES,CancellationProbe.NONE);String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            Collections.reverse(inputs);assertEquals(expected,RasterEvidenceCapture.hashOriginalArgbSet(inputs,work));
            inputs.get(0).image().setRGB(1,1,0xff010203);assertNotEquals(expected,RasterEvidenceCapture.hashOriginalArgbSet(inputs,work));
            assertThrows(IllegalArgumentException.class,()->RasterEvidenceCapture.hashOriginalArgbSet(inputs.subList(0,4),work));
            var wrong=new ArrayList<>(inputs);wrong.set(0,new RasterEvidenceCapture.NamedOriginalRaster(wrong.get(0).palette(),new BufferedImage(3,2,BufferedImage.TYPE_INT_ARGB),transform));
            assertThrows(IllegalArgumentException.class,()->RasterEvidenceCapture.hashOriginalArgbSet(wrong,work));
        }
    }
    @Test void refusalAndCancellationStopBeforeOrInsideTheBoundedPixelRead() {
        var reads=new AtomicInteger();var cancel=new AtomicBoolean();var image=new BufferedImage(600,2,BufferedImage.TYPE_INT_ARGB){
            @Override public int[] getRGB(int x,int y,int w,int h,int[] values,int offset,int scan){reads.addAndGet(w*h);if(reads.get()>=257)cancel.set(true);return super.getRGB(x,y,w,h,values,offset,scan);}
        };
        for(long bytes:new long[]{0,128})try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->RasterEvidenceCapture.hashOriginalArgb(image,work(owner,10000,bytes,CancellationProbe.NONE)));assertEquals(0,reads.get());assertEquals(0,owner.currentBytes());
        }
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var expired=new GeometryWorkContext(10000,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()-1,CancellationProbe.NONE,owner);
            assertThrows(GeometryWorkContext.LimitReached.class,()->RasterEvidenceCapture.hashOriginalArgb(image,expired));assertEquals(0,reads.get());assertEquals(0,owner.currentBytes());
        }
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            assertThrows(GeometryWorkContext.LimitReached.class,()->RasterEvidenceCapture.hashOriginalArgb(image,work(owner,260,AttemptMemoryLedger.MAX_BYTES,CancellationProbe.NONE)));
            assertEquals(256,reads.get());assertEquals(0,owner.currentBytes());
        }
        reads.set(0);cancel.set(false);try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->RasterEvidenceCapture.hashOriginalArgb(image,work(owner,10000,AttemptMemoryLedger.MAX_BYTES,cancel::get)));
            assertTrue(reads.get()>=257&&reads.get()<=512);assertEquals(0,owner.currentBytes());
        }
    }
}
