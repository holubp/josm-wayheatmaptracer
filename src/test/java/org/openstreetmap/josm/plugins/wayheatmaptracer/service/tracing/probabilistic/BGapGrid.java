package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Source partition geometry only; no recovery of native scalar values. */
final class BGapGrid {
    final int width,height;
    final double ox,oy,ux,uy,vx,vy,det;
    final boolean known;
    BGapGrid(int width,int height,double ox,double oy,double ux,double uy,double vx,double vy,boolean known) {
        this.width=width;this.height=height;this.ox=ox;this.oy=oy;this.ux=ux;this.uy=uy;this.vx=vx;this.vy=vy;
        this.det=ux*vy-uy*vx;this.known=known;
        if(width<2||height<2||!Double.isFinite(det)||det==0)throw new IllegalArgumentException("Gap source grid");
    }
    static BGapGrid from(BGapEvidence.Input input,BGapEvidence.WorkCounter work,CancellationProbe cancellation) {
        var e=input.evidence();var t=e.transform();var p=e.resampling();
        var f=e.fields().get(input.fieldKey());
        double scale=t.axisUnit()==RasterMetricTransform.AxisUnit.SOURCE_PIXEL?t.rasterPixelsPerSourcePixel():1;
        var origin=t.pixelCenterToMetric(0,0);
        double ux=t.xAxisEastMetersPerSourcePixel()/scale,uy=t.xAxisNorthMetersPerSourcePixel()/scale;
        double vx=t.yAxisEastMetersPerSourcePixel()/scale,vy=t.yAxisNorthMetersPerSourcePixel()/scale;
        if(p.method().equals("direct-scalar-grid-v1"))return new BGapGrid(f.width(),f.height(),origin.xMeters(),origin.yMeters(),ux,uy,vx,vy,true);
        boolean capture=p.method().equals("exact-analytic-cell-support-strict-bilinear-v2")
                &&p.sourceTransformKind().equals("constructed-local-metric-affine-v1")
                &&e.fields().get(input.fieldKey()).lineage().acquisitionKind()==EvidenceFieldLineage.AcquisitionKind.SYNTHETIC;
        capture&=p.sourceTransformIdentity().length()<=4096&&e.coordinateFrame().projectionId().length()<=1024;
        if(!capture||p.inputWidth()<2||p.inputHeight()<2)
            return new BGapGrid(f.width(),f.height(),origin.xMeters(),origin.yMeters(),ux,uy,vx,vy,false);
        // Bind all six original affine parameters and the factual frame; neither
        // output phase nor a dimensional ratio reconstructs source pixel centers.
        String actual=p.sourceTransformIdentity();
        String prefix=p.sourceTransformKind()+"|"+e.coordinateFrame().projectionId()+"|"
                +Double.toHexString(e.coordinateFrame().origin().latitudeDegrees())+"|"
                +Double.toHexString(e.coordinateFrame().origin().longitudeDegrees())+"|";
        for(int i=0;i<actual.length();i++){if((i&63)==0){work.add(BGapEvidence.Work.CANCELLATION_CHECK,1);cancellation.checkpoint();}work.add(BGapEvidence.Work.CACHE_READ,1);}
        if(actual.startsWith(prefix)) {
            String[] parameters=actual.substring(prefix.length()).split("\\|",-1);
            if(parameters.length==6) {
                double[] values=new double[6];boolean valid=true;
                for(int i=0;i<6;i++){values[i]=Double.parseDouble(parameters[i]);valid&=Double.isFinite(values[i])&&Double.toHexString(values[i]).equals(parameters[i]);work.numeric(4);}
                var transform=SupportedInputRasterTransform.localMetricAffine(e.coordinateFrame(),new RasterPoint(values[0],values[1]),values[2],values[3],values[4],values[5]);
                // The registered capture's original partition is axis aligned. A
                // different unsupported source geometry is explicitly unavailable.
                if(valid&&transform.parameterIdentity().equals(actual)&&values[3]==0&&values[4]==0&&values[2]!=0&&values[5]!=0) {
                    return new BGapGrid(p.inputWidth(),p.inputHeight(),-values[0]/values[2],-values[1]/values[5],
                            1/values[2],0,0,1/values[5],true);
                }
            }
        }
        return new BGapGrid(f.width(),f.height(),origin.xMeters(),origin.yMeters(),ux,uy,vx,vy,false);
    }
    void corners(int x,int y,double[] coordinates,int offset) {
        point(x,y,coordinates,offset);point(x+1,y,coordinates,offset+2);
        point(x+1,y+1,coordinates,offset+4);point(x,y+1,coordinates,offset+6);
    }
    void point(double x,double y,double[] out,int offset) {out[offset]=ox+x*ux+y*vx;out[offset+1]=oy+x*uy+y*vy;}
    double x(double x,double y) {return ((x-ox)*vy-(y-oy)*vx)/det;}
    double y(double x,double y) {return ((y-oy)*ux-(x-ox)*uy)/det;}
    /** Outward affine inverse enclosure for candidate indexing, not coverage authority. */
    void bounds(double x,double y,double[] out) {
        double xl=Math.nextDown(x-ox),xh=Math.nextUp(x-ox),yl=Math.nextDown(y-oy),yh=Math.nextUp(y-oy);
        double dl=Math.nextDown(Math.nextDown(ux*vy)-Math.nextUp(uy*vx));
        double dh=Math.nextUp(Math.nextUp(ux*vy)-Math.nextDown(uy*vx));
        if(dl<=0&&dh>=0)throw new IllegalArgumentException("Unresolved source-grid inverse");
        double nxlo=Math.nextDown(lo(xl,xh,vy)-hi(yl,yh,vx)),nxhi=Math.nextUp(hi(xl,xh,vy)-lo(yl,yh,vx));
        double nylo=Math.nextDown(lo(yl,yh,ux)-hi(xl,xh,uy)),nyhi=Math.nextUp(hi(yl,yh,ux)-lo(xl,xh,uy));
        out[0]=quotientLow(nxlo,nxhi,dl,dh);out[1]=quotientHigh(nxlo,nxhi,dl,dh);
        out[2]=quotientLow(nylo,nyhi,dl,dh);out[3]=quotientHigh(nylo,nyhi,dl,dh);
    }
    private static double lo(double a,double b,double factor){return Math.nextDown(factor>=0?a*factor:b*factor);}
    private static double hi(double a,double b,double factor){return Math.nextUp(factor>=0?b*factor:a*factor);}
    private static double quotientLow(double a,double b,double c,double d){return Math.nextDown(Math.min(Math.min(a/c,a/d),Math.min(b/c,b/d)));}
    private static double quotientHigh(double a,double b,double c,double d){return Math.nextUp(Math.max(Math.max(a/c,a/d),Math.max(b/c,b/d)));}
    int cells(){return Math.multiplyExact(width-1,height-1);}
}
