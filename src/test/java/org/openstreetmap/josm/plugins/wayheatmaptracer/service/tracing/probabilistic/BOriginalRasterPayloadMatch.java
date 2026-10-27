package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Original raw-payload consistency only; no hash/runtime/source8 authority.
 * Borrows all arrays, creates no normal-path payload and preserves raw NaN bits.
 * Full source/cold/failure pricing remains part of the closed capture gate.
 */
final class BOriginalRasterPayloadMatch {
    private BOriginalRasterPayloadMatch(){ }
    static void verifyData(double[] scalar,boolean[] valid,byte[] bytes,byte[] mask,BSharedWork work){
        verifyCapturedData(scalar,valid,bytes,mask,work,null);
    }
    static void verifyCapturedData(double[] scalar,boolean[] valid,byte[] bytes,byte[] mask,
            BSharedWork work,BSharedCaptureAdmission source){
        if(work==null)throw new IllegalArgumentException("Missing raw-payload comparison work");
        poll(work,source);
        if(scalar==null||valid==null||bytes==null||mask==null||scalar.length<4||scalar.length>6561
                ||valid.length!=scalar.length||mask.length!=scalar.length||bytes.length!=8L*scalar.length)
            reject(source);
        work.binding(8192+256L*scalar.length);
        for(int i=0;i<scalar.length;i++){
            poll(work,source);
            long expected=Double.doubleToRawLongBits(scalar[i]);
            for(int b=0;b<8;b++)if((bytes[8*i+b]&255)!=(int)((expected>>>(56-8*b))&255))reject(source);
            if(mask[i]!=(byte)(valid[i]?1:0))reject(source);
        }
        poll(work,source);
    }
    private static void poll(BSharedWork work,BSharedCaptureAdmission source){
        if(source!=null)source.requireRawPayloadLiveData(work);
        work.poll();work.ensureOpen();
        if(source!=null)source.requireRawPayloadLiveData(work);
    }
    private static void reject(BSharedCaptureAdmission source){
        if(source!=null)source.rejectRawPayloadData();
        throw new IllegalArgumentException("Original raw raster payload differs");
    }
}
