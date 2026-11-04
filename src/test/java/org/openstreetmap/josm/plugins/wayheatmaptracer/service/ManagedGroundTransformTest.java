package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.MathContext;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ManagedGroundTransformTest {
    @Test void metricLineEnclosesActualRoundedAndIndependentHighPrecisionInverse() {
        for(double latitude:new double[]{0,49.44,-49.44,80})withTransform(latitude,(transform,mosaic,request,budget,owner)->{
            var center=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(0,0);
            var normal=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(1.7,.9);
            var bounds=transform.metricLineBounds(center,normal,-20,20,budget);
            for(int i=0;i<=32;i++){
                double u=-20+40.0*i/32;
                var metric=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(center.xMeters()+u*normal.xMeters(),center.yMeters()+u*normal.yMeters());
                inside(bounds,transform.rasterAt(metric));
                inside(bounds,referenceInverse(transform,mosaic,u,normal));
            }
            assertThrows(IllegalArgumentException.class,()->transform.metricLineBounds(center,normal,20,-20,budget));
            assertThrows(IllegalArgumentException.class,()->transform.metricLineBounds(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(1e20,1e20),normal,-20,20,budget));
        });
    }

    @Test void curveBoxAuthorityIsOriginalCapsuleSpecificNotAcquiredImageAuthority() {
        withTransform(49.44,(transform,mosaic,request,budget,owner)->{
            var world=request.originalWorldPoints().get(0);
            var actual=new RasterPoint((world.x()-mosaic.originWorldPxX()-.5)*mosaic.virtualRasterScale(),
                (world.y()-mosaic.originWorldPxY()-.5)*mosaic.virtualRasterScale());
            var center=transform.metricAt(actual);
            var n=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(0,1);
            var nearby=transform.metricLineBounds(center,n,-1,1,budget);
            assertTrue(request.authorizesVirtualRasterBox(mosaic,nearby,budget,owner));
            var remote=transform.metricLineBounds(center,n,100,101,budget);
            assertFalse(request.authorizesVirtualRasterBox(mosaic,remote,budget,owner));
            assertTrue(remote.minimumX()>=0&&remote.maximumX()<mosaic.image().getWidth()*mosaic.virtualRasterScale());
            assertTrue(remote.minimumY()>=0&&remote.maximumY()<mosaic.image().getHeight()*mosaic.virtualRasterScale());
        });
    }

    @FunctionalInterface interface TransformCheck {
        void run(ManagedGroundTransform transform,TileHeatmapSampler.TileMosaic mosaic,ManagedSourceRequestReceipt request,
            EmissionWorkBudget budget,AttemptMemoryLedger.Owner owner);
    }
    private static void withTransform(double latitude,TransformCheck check){
        var projection=Projections.getProjectionByCode("EPSG:3857");int zoom=15;
        var source=List.of(projection.latlon2eastNorth(new LatLon(latitude,14)),projection.latlon2eastNorth(new LatLon(latitude+.00005,14.00005)));
        var parameters=new TileHeatmapSampler.SamplingParameters(zoom,latitude,TileHeatmapSampler.metersPerPixel(zoom,latitude),28,6,56,12);
        double scale=parameters.metersPerPixel()/TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL*TileHeatmapSampler.REFERENCE_RASTER_SCALE;
        var ledger=AttemptMemoryLedger.production();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var owner=ledger.rootOwner()){
            var request=ManagedSourceRequestReceipt.captureBeforeFetch(source,"hot",zoom,parameters,scale,projection,p->world(projection.eastNorth2latlon(p),zoom),budget,owner);
            var mosaic=new TileHeatmapSampler.TileMosaic("hot",zoom,request.minTileX()*(double)TileHeatmapSampler.TILE_SIZE,request.minTileY()*(double)TileHeatmapSampler.TILE_SIZE,
                new BufferedImage((request.maxTileX()-request.minTileX()+1)*TileHeatmapSampler.TILE_SIZE,(request.maxTileY()-request.minTileY()+1)*TileHeatmapSampler.TILE_SIZE,BufferedImage.TYPE_INT_ARGB),
                List.of(),Map.of(),parameters,true,scale);
            var transform=ManagedGroundTransform.fromSource(mosaic,request,budget,owner);
            check.run(transform,mosaic,request,budget,owner);
        }
        assertEquals(0,ledger.currentBytes());
    }
    private static void inside(ManagedGroundTransform.LineBounds b,RasterPoint p){
        assertTrue(p.x()>=b.minimumX()&&p.x()<=b.maximumX(),()->"X outside bound: "+p.x());
        assertTrue(p.y()>=b.minimumY()&&p.y()<=b.maximumY(),()->"Y outside bound: "+p.y());
    }
    private static final MathContext REFERENCE=new MathContext(90);
    private static BigDecimal bd(double x){return new BigDecimal(x);}
    /** Independent 90-digit Taylor sin and sqrt-reduced atanh log; no Math sin/log or interval helper. */
    private static BigDecimal sinReference(BigDecimal x){
        BigDecimal term=x,sum=x,xx=x.multiply(x,REFERENCE);
        for(int k=1;k<100;k++){term=term.multiply(xx,REFERENCE).negate().divide(BigDecimal.valueOf((2L*k)*(2L*k+1)),REFERENCE);sum=sum.add(term,REFERENCE);}
        return sum;
    }
    private static BigDecimal logReference(BigDecimal x){
        for(int i=0;i<8;i++)x=x.sqrt(REFERENCE);
        var z=x.subtract(BigDecimal.ONE,REFERENCE).divide(x.add(BigDecimal.ONE,REFERENCE),REFERENCE);
        var zz=z.multiply(z,REFERENCE);var term=z;var sum=z;
        for(int i=1;i<80;i++){term=term.multiply(zz,REFERENCE);sum=sum.add(term.divide(BigDecimal.valueOf(2L*i+1),REFERENCE),REFERENCE);}
        return sum.multiply(BigDecimal.valueOf(512),REFERENCE);
    }
    private static RasterPoint referenceInverse(ManagedGroundTransform transform,TileHeatmapSampler.TileMosaic mosaic,double u,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint n){
        var frame=transform.frame();var cert=frame.distortionCertificate();
        var east=bd(u).multiply(bd(n.xMeters()),REFERENCE);var north=bd(u).multiply(bd(n.yMeters()),REFERENCE);
        var lat=bd(frame.origin().latitudeDegrees()).add(north.divide(bd(cert.northMetersPerRadian()),REFERENCE).multiply(bd(0x1.ca5dc1a63c1f8p5),REFERENCE),REFERENCE);
        var lon=bd(frame.origin().longitudeDegrees()).add(east.divide(bd(cert.eastMetersPerRadian()),REFERENCE).multiply(bd(0x1.ca5dc1a63c1f8p5),REFERENCE),REFERENCE);
        var sine=sinReference(lat.multiply(bd(0x1.1df46a2529d39p-6),REFERENCE));
        var ratio=BigDecimal.ONE.add(sine,REFERENCE).divide(BigDecimal.ONE.subtract(sine,REFERENCE),REFERENCE);
        var size=bd(TileHeatmapSampler.TILE_SIZE*Math.scalb(1.0,mosaic.zoom()));
        var wy=bd(.5).subtract(logReference(ratio).divide(bd(4*Math.PI),REFERENCE),REFERENCE).multiply(size,REFERENCE);
        var wx=lon.add(bd(180),REFERENCE).divide(bd(360),REFERENCE).multiply(size,REFERENCE);
        var x=wx.subtract(bd(mosaic.originWorldPxX()),REFERENCE).subtract(bd(.5),REFERENCE).multiply(bd(mosaic.virtualRasterScale()),REFERENCE);
        var y=wy.subtract(bd(mosaic.originWorldPxY()),REFERENCE).subtract(bd(.5),REFERENCE).multiply(bd(mosaic.virtualRasterScale()),REFERENCE);
        return new RasterPoint(x.doubleValue(),y.doubleValue());
    }
    @Test void wholeTileFramePreservesCenterPhaseAndBoundsLatitudeExtremePitchAndCurve() {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        int zoom = 15;
        var source = List.of(projection.latlon2eastNorth(new LatLon(49.44, 14.0)),
                projection.latlon2eastNorth(new LatLon(49.4401, 14.0002)));
        var parameters = new TileHeatmapSampler.SamplingParameters(zoom, 49.44,
                TileHeatmapSampler.metersPerPixel(zoom, 49.44), 28.0, 6.0, 56, 12);
        double scale = parameters.metersPerPixel()
                / TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL
                * TileHeatmapSampler.REFERENCE_RASTER_SCALE;
        var owner = AttemptMemoryLedger.production().rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try {
            var receipt = ManagedSourceRequestReceipt.captureBeforeFetch(source, "hot", zoom,
                    parameters, scale, projection,
                    point -> world(projection.eastNorth2latlon(point), zoom), budget, owner);
            int width = (receipt.maxTileX() - receipt.minTileX() + 1) * TileHeatmapSampler.TILE_SIZE;
            int height = (receipt.maxTileY() - receipt.minTileY() + 1) * TileHeatmapSampler.TILE_SIZE;
            var mosaic = new TileHeatmapSampler.TileMosaic("hot", zoom,
                    receipt.minTileX() * (double) TileHeatmapSampler.TILE_SIZE,
                    receipt.minTileY() * (double) TileHeatmapSampler.TILE_SIZE,
                    new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB),
                    List.of(), Map.of(), parameters, true, scale);
            var transform = ManagedGroundTransform.fromSource(mosaic, receipt, budget, owner);
            RasterPoint origin = new RasterPoint(0.0, 0.0);
            double worldSize = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, zoom);
            var centerWorld = new Point2D.Double(mosaic.originWorldPxX() + 0.5,
                    mosaic.originWorldPxY() + 0.5);
            var expectedGeographic = geographic(centerWorld, worldSize);
            assertEquals(transform.frame().toMetric(expectedGeographic), transform.metricAt(origin));
            RasterPoint fractional = new RasterPoint(1.25 * scale, 2.75 * scale);
            RasterPoint roundTrip = transform.rasterAt(transform.metricAt(fractional));
            assertEquals(fractional.x(), roundTrip.x(), 1e-5);
            assertEquals(fractional.y(), roundTrip.y(), 1e-5);
            double k = 2.0 * Math.PI / worldSize;
            double eastScale = transform.frame().distortionCertificate().eastMetersPerRadian();
            double northScale = transform.frame().distortionCertificate().northMetersPerRadian();
            for (double y : List.of(mosaic.originWorldPxY() + 0.5,
                    mosaic.originWorldPxY() + height - 0.5)) {
                double latitude = geographic(new Point2D.Double(centerWorld.x, y), worldSize)
                        .latitudeDegrees();
                double localPitch = k * Math.max(eastScale,
                        northScale * Math.cos(Math.toRadians(latitude)));
                assertTrue(transform.maximumNativePixelPitchMeters() >= localPitch);
                RasterPoint localRaster = new RasterPoint(0.0,
                        (y - mosaic.originWorldPxY() - 0.5) * scale);
                assertEquals(localPitch, transform.nativePixelPitchAt(localRaster), 1e-8);
                assertEquals(transform.nativePixelPitchAt(localRaster) / scale,
                        transform.virtualPixelPitchAt(localRaster), 0.0);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> transform.nativePixelPitchAt(new RasterPoint(-scale, 0)));
            RasterPoint start = new RasterPoint(20.0 * scale, 30.0 * scale);
            RasterPoint end = new RasterPoint(200.0 * scale, 300.0 * scale);
            RasterPoint mid = new RasterPoint((start.x() + end.x()) * 0.5,
                    (start.y() + end.y()) * 0.5);
            double actualDeviation = Math.abs(transform.metricAt(mid).yMeters()
                    - (transform.metricAt(start).yMeters()
                        + transform.metricAt(end).yMeters()) * 0.5);
            assertTrue(transform.maximumNorthChordErrorMeters(start, end) >= actualDeviation);
        } finally {
            owner.close();
        }
    }

    private static Point2D.Double world(LatLon point, int zoom) {
        double size = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, zoom);
        double sine = Math.sin(Math.toRadians(point.lat()));
        return new Point2D.Double((point.lon() + 180.0) / 360.0 * size,
                (0.5 - Math.log((1.0 + sine) / (1.0 - sine)) / (4.0 * Math.PI)) * size);
    }

    private static GeographicPoint geographic(Point2D.Double world, double size) {
        return new GeographicPoint(
                Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * world.y / size)))),
                world.x / size * 360.0 - 180.0);
    }
}
