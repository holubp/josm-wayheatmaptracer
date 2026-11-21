package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.awt.image.BufferedImage;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Slide-time metadata only. The ordinary Legacy result remains the owner of its existing raster. */
public record LegacyVisibleSource(double minimumEast, double minimumNorth,
        double maximumEast, double maximumNorth, double projectionUnitsPerViewPixel,
        double groundMetersPerViewPixel, OptionalDouble nativePitchMeters,
        String projectionCode, VisibleSourceEpoch.Receipt sourceReceipt,
        OptionalDouble decisionRadiusMeters,OptionalDouble sampleStepMeters) {
    /** Historical captures retain honest absence of their effective slide-time sampling metadata. */
    public LegacyVisibleSource(double minimumEast,double minimumNorth,double maximumEast,double maximumNorth,
            double projectionUnitsPerViewPixel,double groundMetersPerViewPixel,OptionalDouble nativePitchMeters,
            String projectionCode,VisibleSourceEpoch.Receipt sourceReceipt) {
        this(minimumEast,minimumNorth,maximumEast,maximumNorth,projectionUnitsPerViewPixel,groundMetersPerViewPixel,nativePitchMeters,projectionCode,sourceReceipt,OptionalDouble.empty(),OptionalDouble.empty());
    }
    public LegacyVisibleSource {
        if (!Double.isFinite(minimumEast) || !Double.isFinite(minimumNorth)
                || !Double.isFinite(maximumEast) || !Double.isFinite(maximumNorth)
                || minimumEast >= maximumEast || minimumNorth >= maximumNorth
                || !Double.isFinite(projectionUnitsPerViewPixel) || projectionUnitsPerViewPixel <= 0
                || !Double.isFinite(groundMetersPerViewPixel) || groundMetersPerViewPixel <= 0
                || projectionCode == null || projectionCode.isBlank()) {
            throw new IllegalArgumentException("Original visible capture metadata is incomplete");
        }
        nativePitchMeters = nativePitchMeters == null ? OptionalDouble.empty() : nativePitchMeters;
        decisionRadiusMeters=decisionRadiusMeters==null?OptionalDouble.empty():decisionRadiusMeters;
        sampleStepMeters=sampleStepMeters==null?OptionalDouble.empty():sampleStepMeters;
        if(decisionRadiusMeters.isPresent()&&(!Double.isFinite(decisionRadiusMeters.getAsDouble())||decisionRadiusMeters.getAsDouble()<=0)
                ||sampleStepMeters.isPresent()&&(!Double.isFinite(sampleStepMeters.getAsDouble())||sampleStepMeters.getAsDouble()<=0))
            throw new IllegalArgumentException("Original visible sampling metadata is invalid");
        if (nativePitchMeters.isPresent() && (!Double.isFinite(nativePitchMeters.getAsDouble())
                || nativePitchMeters.getAsDouble() <= 0)) {
            throw new IllegalArgumentException("Original native pitch is invalid");
        }
    }
    /** Freezes pixels only for an explicit operation, after validating the original source receipt. */
    LiveBPreviewService.VisibleRaster freeze(BufferedImage raster, String sourceIdentity) {
        if (sourceReceipt == null) {
            throw new IllegalStateException("The original visible source has no unchanged-pixel receipt");
        }
        sourceReceipt.owner().requireCurrent(sourceReceipt);
        if (raster == null) throw new IllegalStateException("The original visible capture is unavailable");
        int[] pixels = raster.getRGB(0, 0, raster.getWidth(), raster.getHeight(), null, 0, raster.getWidth());
        sourceReceipt.owner().requireCurrent(sourceReceipt);
        return new LiveBPreviewService.VisibleRaster(raster.getWidth(), raster.getHeight(), pixels,
                minimumEast, minimumNorth, maximumEast, maximumNorth, projectionUnitsPerViewPixel,
                groundMetersPerViewPixel, nativePitchMeters, sourceIdentity, projectionCode, sourceReceipt);
    }
    /** Optional smoothing copy, admitted before allocation and cancellable in <=256 pixel chunks.
     * The old VisibleRaster constructor/accessor cloning contract remains untouched.
     */
    public SmoothingRaster freeze(BufferedImage raster,String sourceIdentity,GeometryWorkContext work) {
        return freeze(raster,sourceIdentity,work,work.owner());
    }
    public SmoothingRaster freeze(BufferedImage raster,String sourceIdentity,GeometryWorkContext work,AttemptMemoryLedger.Owner owner) {
        if(sourceReceipt==null)throw new IllegalStateException("Original visible source has no unchanged-pixel receipt");
        sourceReceipt.owner().requireCurrent(sourceReceipt);
        if(raster==null||sourceIdentity==null||sourceIdentity.isBlank())throw new IllegalStateException("Original visible raster is unavailable");
        int width=raster.getWidth(),height=raster.getHeight(),count=Math.multiplyExact(width,height);
        long bytes=Math.addExact(4096L,AttemptMemoryLedger.arrayBytes(count,4));
        try(var admitted=work.reserveOn(owner,"original-visible-pixels",bytes)) {
            var pixels=new int[count];var hash=java.security.MessageDigest.getInstance("SHA-256");
            for(int y=0;y<height;y++)for(int x=0;x<width;x+=256) {
                int length=Math.min(256,width-x);work.visit(GeometryWorkContext.Kind.COPY,length);
                raster.getRGB(x,y,length,1,pixels,y*width+x,width);
                for(int i=0;i<length;i++) {
                    work.visit(GeometryWorkContext.Kind.HASH,1);int argb=pixels[y*width+x+i];
                    hash.update((byte)(argb>>>24));hash.update((byte)(argb>>>16));hash.update((byte)(argb>>>8));hash.update((byte)argb);
                }
            }
            sourceReceipt.owner().requireCurrent(sourceReceipt);
            var result=new SmoothingRaster(width,height,pixels,this,sourceIdentity,java.util.HexFormat.of().formatHex(hash.digest()));
            admitted.adopt(result);return result;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** Private owned backing pixels; callers can obtain scalar pixels or a separately admitted copy. */
    public static final class SmoothingRaster {
        private final int width,height;
        private final int[] pixels;
        private final LegacyVisibleSource metadata;
        private final String sourceIdentity,inputByteHash;
        private SmoothingRaster(int width,int height,int[] pixels,LegacyVisibleSource metadata,String sourceIdentity,String inputByteHash) {
            this.width=width;this.height=height;this.pixels=pixels;this.metadata=metadata;this.sourceIdentity=sourceIdentity;this.inputByteHash=inputByteHash;
        }
        public int width(){return width;}public int height(){return height;}
        public LegacyVisibleSource metadata(){return metadata;}public String sourceIdentity(){return sourceIdentity;}
        /** SHA-256 of exactly the copied row-major four-byte ARGB pixel values. */
        public String inputByteHash(){return inputByteHash;}
        public void requireCurrent(){metadata.sourceReceipt().owner().requireCurrent(metadata.sourceReceipt());}
        public int argbAt(int x,int y,GeometryWorkContext work) {
            if(x<0||y<0||x>=width||y>=height)throw new IndexOutOfBoundsException();work.visit(GeometryWorkContext.Kind.COPY,1);return pixels[y*width+x];
        }
        public BufferedImage copyImage(GeometryWorkContext work) {
            return copyImage(work,work.owner());
        }
        public BufferedImage copyImage(GeometryWorkContext work,AttemptMemoryLedger.Owner owner) {
            requireCurrent();long bytes=Math.addExact(4096L,AttemptMemoryLedger.arrayBytes(pixels.length,4));
            try(var admitted=work.reserveOn(owner,"visible-mapping-image",bytes)) {
                var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<height;y++)for(int x=0;x<width;x+=256) {
                    int length=Math.min(256,width-x);work.visit(GeometryWorkContext.Kind.COPY,length);image.setRGB(x,y,length,1,pixels,y*width+x,width);
                }
                requireCurrent();admitted.adopt(image);return image;
            }
        }
    }
}
