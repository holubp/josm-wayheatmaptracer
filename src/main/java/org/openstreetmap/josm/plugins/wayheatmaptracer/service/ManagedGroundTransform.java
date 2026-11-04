package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Exact captured Web Mercator pixel-center mapping into one certified WGS84 local frame. */
public final class ManagedGroundTransform {
    private final TileHeatmapSampler.TileMosaic mosaic;
    private final LocalMetricFrame frame;
    private final double worldSize;
    private final double maximumNativePixelPitchMeters;
    private final AttemptMemoryLedger.Owner owner;

    private ManagedGroundTransform(TileHeatmapSampler.TileMosaic mosaic, LocalMetricFrame frame,
            double worldSize, double maximumNativePixelPitchMeters,
            AttemptMemoryLedger.Owner owner) {
        this.mosaic = mosaic;
        this.frame = frame;
        this.worldSize = worldSize;
        this.maximumNativePixelPitchMeters = maximumNativePixelPitchMeters;
        this.owner = owner;
    }

    /** Certifies the entire originally requested tile domain, rather than one representative latitude. */
    public static ManagedGroundTransform fromPreparedCapture(
            TileHeatmapSampler.PreparedManagedCapture capture, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner parent) {
        Objects.requireNonNull(capture, "prepared source capture");
        return fromSource(capture.selectedMosaic(), capture.originalRequest(), budget, parent);
    }

    static ManagedGroundTransform fromSource(TileHeatmapSampler.TileMosaic mosaic,
            ManagedSourceRequestReceipt request, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner parent) {
        Objects.requireNonNull(request, "original request receipt").requireMatches(mosaic);
        Objects.requireNonNull(budget, "shared work budget");
        Objects.requireNonNull(parent, "attempt owner");
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var owner = parent.child("managed-ground-transform");
        boolean complete = false;
        try {
            var reservation = owner.reserve(4096L);
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 8);
            double worldSize = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, mosaic.zoom());
            double westWorld = mosaic.originWorldPxX();
            double eastWorld = westWorld + mosaic.image().getWidth();
            double northWorld = mosaic.originWorldPxY();
            double southWorld = northWorld + mosaic.image().getHeight();
            if (!(westWorld >= 0.0 && eastWorld <= worldSize && northWorld >= 0.0
                    && southWorld <= worldSize)) {
                throw new IllegalArgumentException("Managed tile domain crosses Web Mercator world boundary");
            }
            double west = longitude(westWorld, worldSize);
            double east = longitude(eastWorld, worldSize);
            double north = latitude(northWorld, worldSize);
            double south = latitude(southWorld, worldSize);
            // The pad encloses roundoff at inverse/forward boundaries; it remains in the certificate.
            double pad = 1e-9;
            GeographicPoint southwest = new GeographicPoint(south - pad, west - pad);
            GeographicPoint northeast = new GeographicPoint(north + pad, east + pad);
            GeographicPoint center = new GeographicPoint((south + north) * 0.5,
                    (west + east) * 0.5);
            LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                    center, southwest, northeast);
            double k = 2.0 * Math.PI / worldSize;
            double nearEquator = south <= 0.0 && north >= 0.0 ? 0.0
                    : Math.min(Math.abs(south), Math.abs(north));
            double maximumCosine = Math.cos(Math.toRadians(nearEquator));
            double eastScale = frame.distortionCertificate().eastMetersPerRadian();
            double northScale = frame.distortionCertificate().northMetersPerRadian();
            double pitch = Math.nextUp(k * Math.max(eastScale, northScale * maximumCosine)
                    * (1.0 + 1e-12) + 1e-9);
            if (!Double.isFinite(pitch) || pitch <= 0.0) {
                throw new IllegalArgumentException("Managed native pitch is not certified");
            }
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, request.originalSource().size());
            if (!request.originalSearchWithin(westWorld + 0.5, northWorld + 0.5,
                    eastWorld - 0.5, southWorld - 0.5)) {
                throw new IllegalArgumentException("Original search leaves acquired source cells");
            }
            var result = new ManagedGroundTransform(mosaic, frame, worldSize, pitch, owner);
            reservation.adopt(result);
            complete = true;
            return result;
        } finally {
            if (!complete) owner.close();
        }
    }

    /** Maps a producer virtual-profile raster point through the native image center phase. */
    public MetricPoint metricAt(RasterPoint virtualRasterPoint) {
        requireOpen();
        Objects.requireNonNull(virtualRasterPoint, "virtual raster point");
        double worldX = mosaic.originWorldPxX()
                + virtualRasterPoint.x() / mosaic.virtualRasterScale() + 0.5;
        double worldY = mosaic.originWorldPxY()
                + virtualRasterPoint.y() / mosaic.virtualRasterScale() + 0.5;
        return frame.toMetric(new GeographicPoint(latitude(worldY, worldSize),
                longitude(worldX, worldSize)));
    }

    /** Inverts the same captured frame and pixel-center phase. */
    public RasterPoint rasterAt(MetricPoint metricPoint) {
        requireOpen();
        GeographicPoint geographic = frame.toGeographic(Objects.requireNonNull(metricPoint));
        double latRad = Math.toRadians(geographic.latitudeDegrees());
        double sinLat = Math.sin(latRad);
        double worldY = (0.5 - Math.log((1.0 + sinLat) / (1.0 - sinLat))
                / (4.0 * Math.PI)) * worldSize;
        double worldX = (geographic.longitudeDegrees() + 180.0) / 360.0 * worldSize;
        double x = (worldX - mosaic.originWorldPxX() - 0.5) * mosaic.virtualRasterScale();
        double y = (worldY - mosaic.originWorldPxY() - 0.5) * mosaic.virtualRasterScale();
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Managed metric coordinate has no finite raster inverse");
        }
        return new RasterPoint(x, y);
    }

    /** Conservative maximum ground displacement of one native source-image pixel on the whole domain. */
    public double maximumNativePixelPitchMeters() {
        requireOpen();
        return maximumNativePixelPitchMeters;
    }
    /** Outward lower bound on the local maximum-axis native pitch over the acquired domain. */
    public double minimumNativePixelPitchMeters() {
        requireOpen();
        double north = Math.abs(Math.toRadians(latitude(mosaic.originWorldPxY(), worldSize)));
        double south = Math.abs(Math.toRadians(latitude(mosaic.originWorldPxY() + mosaic.image().getHeight(), worldSize)));
        double minimumCosine = Math.cos(Math.max(north, south));
        double k = 2.0 * Math.PI / worldSize;
        return Math.nextDown(k * Math.max(frame.distortionCertificate().eastMetersPerRadian(),
                frame.distortionCertificate().northMetersPerRadian() * minimumCosine)
                * (1.0 - 1e-12) - 1e-9);
    }

    /** Local ground pitch of a native image pixel at one certified producer raster point. */
    public double nativePixelPitchAt(RasterPoint virtualRasterPoint) {
        requireOpen();
        Objects.requireNonNull(virtualRasterPoint, "virtual raster point");
        double nativeX = virtualRasterPoint.x() / mosaic.virtualRasterScale();
        double nativeY = virtualRasterPoint.y() / mosaic.virtualRasterScale();
        if (!Double.isFinite(nativeX) || !Double.isFinite(nativeY)
                || nativeX < 0.0 || nativeY < 0.0
                || nativeX > mosaic.image().getWidth() - 1.0
                || nativeY > mosaic.image().getHeight() - 1.0) {
            throw new IllegalArgumentException("Managed pitch point leaves acquired source cells");
        }
        double worldY = mosaic.originWorldPxY() + nativeY + 0.5;
        double latitude = latitude(worldY, worldSize);
        double k = 2.0 * Math.PI / worldSize;
        double east = frame.distortionCertificate().eastMetersPerRadian();
        double north = frame.distortionCertificate().northMetersPerRadian()
                * Math.cos(Math.toRadians(latitude));
        double local = Math.nextUp(k * Math.max(east, north) * (1.0 + 1e-12));
        if (!Double.isFinite(local) || local <= 0.0
                || local > maximumNativePixelPitchMeters) {
            throw new IllegalStateException("Managed local pitch exceeds certified domain bound");
        }
        return local;
    }

    /** Local ground pitch of one virtual profile-raster pixel, distinct from native tile pitch. */
    public double virtualPixelPitchAt(RasterPoint virtualRasterPoint) {
        return nativePixelPitchAt(virtualRasterPoint) / mosaic.virtualRasterScale();
    }

    /** Analytic local ground displacement per one original virtual-raster normal unit. */
    public double normalPitchAt(RasterPoint point, RasterPoint normal) {
        requireOpen();
        double worldY = mosaic.originWorldPxY() + point.y() / mosaic.virtualRasterScale() + 0.5;
        double lat = Math.toRadians(latitude(worldY, worldSize));
        double k = 2.0 * Math.PI / worldSize / mosaic.virtualRasterScale();
        return Math.hypot(normal.x() * k * frame.distortionCertificate().eastMetersPerRadian(),
                normal.y() * k * frame.distortionCertificate().northMetersPerRadian() * Math.cos(lat));
    }
    /** Unit transverse direction of the actual mapped raster segment at this probe. */
    public RasterPoint transverseAt(RasterPoint probe, RasterPoint from, RasterPoint to) {
        requireOpen();
        double worldY = mosaic.originWorldPxY() + probe.y() / mosaic.virtualRasterScale() + 0.5;
        double lat = Math.toRadians(latitude(worldY, worldSize));
        double east = (to.x() - from.x()) * frame.distortionCertificate().eastMetersPerRadian();
        double north = -(to.y() - from.y()) * frame.distortionCertificate().northMetersPerRadian() * Math.cos(lat);
        double length = Math.hypot(east, north);
        if (!(length > 0.0)) throw new IllegalArgumentException("Managed connector direction is zero");
        return new RasterPoint(-north / length, east / length);
    }

    /** Analytic deviation of the transformed raster segment from its metric endpoint chord. */
    public double maximumNorthChordErrorMeters(RasterPoint start, RasterPoint end) {
        requireOpen();
        double worldDeltaY = (end.y() - start.y()) / mosaic.virtualRasterScale();
        double k = 2.0 * Math.PI / worldSize;
        double northScale = frame.distortionCertificate().northMetersPerRadian();
        return Math.nextUp(northScale * k * k * worldDeltaY * worldDeltaY / 16.0 + 1e-9);
    }

    public LocalMetricFrame frame() { requireOpen(); return frame; }

    /** Conservative enclosure, never authority or a claim that a rounded map is injective. */
    public record LineBounds(double minimumX, double maximumX, double minimumY, double maximumY) {
        public LineBounds {
            if (!Double.isFinite(minimumX) || !Double.isFinite(maximumX) || !Double.isFinite(minimumY)
                    || !Double.isFinite(maximumY) || minimumX > maximumX || minimumY > maximumY)
                throw new IllegalArgumentException("Unresolved metric-line enclosure");
        }
    }

    /**
     * Encloses the actual Java17 inverse chain for M(u)=center+u*normal. Each binary operation
     * rounds outward by one adjacent value. sin/log are monotone on the admitted domain;
     * Java Math specifies <=1 ulp error, enclosed by two ulps plus directed endpoint rounding.
     * The strict frame and Java17 conversions use the captured single-multiply constants.
     * No radians/degrees cancellation, longitude normalization seam or pole is assumed away.
     */
    public LineBounds metricLineBounds(MetricPoint center, MetricPoint normal, double from, double to,
            EmissionWorkBudget budget) {
        requireOpen(); budget.charge(EmissionWorkBudget.Operation.PREPARATION, 256);
        if (!inverseNumericalIdentitySupported()) throw new IllegalArgumentException("Unsupported inverse numerical identity");
        I u = new I(from, to);
        I east = I.point(center.xMeters()).add(u.mul(I.point(normal.xMeters())));
        I north = I.point(center.yMeters()).add(u.mul(I.point(normal.yMeters())));
        return inverseBounds(east,north);
    }
    /** Encloses continuous exact-real H and its actual two-stage rounded anchor/query construction. */
    public LineBounds metricFamilyBounds(MetricPoint origin,MetricPoint direction,MetricPoint normal,
            double firstS,double lastS,double firstU,double lastU,EmissionWorkBudget budget) {
        requireOpen();budget.charge(EmissionWorkBudget.Operation.PREPARATION,256);
        if(!inverseNumericalIdentitySupported())throw new IllegalArgumentException("Unsupported inverse numerical identity");
        I s=new I(firstS,lastS),u=new I(firstU,lastU);
        I east=I.point(origin.xMeters()).add(s.mul(I.point(direction.xMeters()))).add(u.mul(I.point(normal.xMeters())));
        I north=I.point(origin.yMeters()).add(s.mul(I.point(direction.yMeters()))).add(u.mul(I.point(normal.yMeters())));
        return inverseBounds(east,north);
    }
    private LineBounds inverseBounds(I east,I north) {
        var certificate = frame.distortionCertificate();
        I latitude = I.point(frame.origin().latitudeDegrees()).add(north.div(certificate.northMetersPerRadian()).mul(I.point(0x1.ca5dc1a63c1f8p5)));
        I longitude = I.point(frame.origin().longitudeDegrees()).add(east.div(certificate.eastMetersPerRadian()).mul(I.point(0x1.ca5dc1a63c1f8p5)));
        if (longitude.lo <= -180 || longitude.hi >= 180
                || latitude.lo < certificate.southWest().latitudeDegrees() || latitude.hi > certificate.northEast().latitudeDegrees()
                || longitude.lo < certificate.southWest().longitudeDegrees() || longitude.hi > certificate.northEast().longitudeDegrees())
            throw new IllegalArgumentException("Metric-line interval leaves captured geographic domain");
        I radians = latitude.mul(I.point(0x1.1df46a2529d39p-6));
        if (!(radians.lo > -Math.PI / 2 && radians.hi < Math.PI / 2)) throw new IllegalArgumentException("Inverse interval meets pole");
        I sine = radians.sin();
        I ratio = I.point(1).add(sine).divide(I.point(1).subtract(sine));
        I worldY = I.point(.5).subtract(ratio.log().div(4 * Math.PI)).mul(I.point(worldSize));
        I worldX = longitude.add(I.point(180)).div(360).mul(I.point(worldSize));
        I x = worldX.subtract(I.point(mosaic.originWorldPxX())).subtract(I.point(.5)).mul(I.point(mosaic.virtualRasterScale()));
        I y = worldY.subtract(I.point(mosaic.originWorldPxY())).subtract(I.point(.5)).mul(I.point(mosaic.virtualRasterScale()));
        return new LineBounds(x.lo, x.hi, y.lo, y.hi);
    }
    private boolean inverseNumericalIdentitySupported() {
        // This experimental certificate targets the reviewed OpenJDK17 multiply conversion law.
        // Other runtime identities retain actual point facts but receive no inverse enclosure.
        return frame.hasCompleteNumericalIdentity() && Runtime.version().feature()==17
            && System.getProperty("java.vm.name","").startsWith("OpenJDK")
            && Double.doubleToRawLongBits(Math.toRadians(1))==Double.doubleToRawLongBits(0x1.1df46a2529d39p-6)
            && Double.doubleToRawLongBits(Math.toDegrees(1))==Double.doubleToRawLongBits(0x1.ca5dc1a63c1f8p5);
    }

    private record I(double lo, double hi) {
        I { if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo > hi) throw new IllegalArgumentException("Unresolved inverse interval"); }
        static I point(double value) { return new I(value, value); }
        I add(I b) { return new I(Math.nextDown(lo + b.lo), Math.nextUp(hi + b.hi)); }
        I subtract(I b) { return new I(Math.nextDown(lo - b.hi), Math.nextUp(hi - b.lo)); }
        I mul(I b) {
            double a = lo * b.lo, c = lo * b.hi, d = hi * b.lo, e = hi * b.hi;
            return new I(Math.nextDown(Math.min(Math.min(a,c),Math.min(d,e))), Math.nextUp(Math.max(Math.max(a,c),Math.max(d,e))));
        }
        I div(double positive) { return divide(point(positive)); }
        I divide(I positive) {
            if (!(positive.lo > 0)) throw new IllegalArgumentException("Inverse interval denominator is not positive");
            return mul(new I(Math.nextDown(1 / positive.hi), Math.nextUp(1 / positive.lo)));
        }
        I sin() { return new I(down(Math.sin(lo)), up(Math.sin(hi))); }
        I log() {
            if (!(lo > 0)) throw new IllegalArgumentException("Inverse log interval is not positive");
            return new I(down(Math.log(lo)), up(Math.log(hi)));
        }
        private static double down(double x) { return Math.nextDown(x - 2 * Math.ulp(x)); }
        private static double up(double x) { return Math.nextUp(x + 2 * Math.ulp(x)); }
    }

    private static double longitude(double worldX, double size) {
        return worldX / size * 360.0 - 180.0;
    }

    private static double latitude(double worldY, double size) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * worldY / size))));
    }

    private void requireOpen() {
        if (owner.isClosed()) throw new IllegalStateException("Managed ground transform is closed");
    }
}
