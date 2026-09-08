package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

/** Horizontal analytic sine branch sampled independently from production tracing. */
public record SineCurve(double startX, double endX, double amplitude, double wavelength) implements AnalyticCurve {
    @Override
    public V022Point pointAtFraction(double fraction) {
        double x = startX + (endX - startX) * Math.max(0.0, Math.min(1.0, fraction));
        return new V022Point(x, amplitude * Math.sin(2.0 * Math.PI * x / wavelength));
    }

    @Override
    public double lengthMeters() {
        double length = 0.0;
        V022Point previous = pointAtFraction(0.0);
        for (int index = 1; index <= 8_192; index++) {
            V022Point next = pointAtFraction(index / 8_192.0);
            length += previous.distanceTo(next);
            previous = next;
        }
        return length;
    }
}
