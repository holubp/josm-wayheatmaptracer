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
            for (var polygon : request.originalWorldPixelDecisionRegion().polygons()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, polygon.size());
                for (MetricPoint point : polygon) {
                    if (point.xMeters() < westWorld + 0.5 || point.xMeters() > eastWorld - 0.5
                            || point.yMeters() < northWorld + 0.5
                            || point.yMeters() > southWorld - 0.5) {
                        throw new IllegalArgumentException("Original search leaves acquired source cells");
                    }
                }
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

    /** Analytic deviation of the transformed raster segment from its metric endpoint chord. */
    public double maximumNorthChordErrorMeters(RasterPoint start, RasterPoint end) {
        requireOpen();
        double worldDeltaY = (end.y() - start.y()) / mosaic.virtualRasterScale();
        double k = 2.0 * Math.PI / worldSize;
        double northScale = frame.distortionCertificate().northMetersPerRadian();
        return Math.nextUp(northScale * k * k * worldDeltaY * worldDeltaY / 16.0 + 1e-9);
    }

    public LocalMetricFrame frame() { requireOpen(); return frame; }

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
