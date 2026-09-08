package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.util.function.IntToDoubleFunction;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

/** Converts immutable rendered pixels to scalar intensity before any scalar-domain filtering. */
public final class ScalarEvidenceFactory {
    private ScalarEvidenceFactory() {
    }

    /** Maps each valid ARGB pixel through a named semantic intensity mapping. */
    public static ScalarEvidenceField mapArgb(int width, int height, int[] argb, boolean[] valid,
        IntToDoubleFunction mapping, EvidenceFieldLineage lineage) {
        if (argb == null || valid == null || mapping == null || argb.length != valid.length) {
            throw new IllegalArgumentException("ARGB evidence inputs are inconsistent");
        }
        double[] values = new double[argb.length];
        for (int index = 0; index < argb.length; index++) {
            values[index] = valid[index] ? mapping.applyAsDouble(argb[index]) : Double.NaN;
        }
        return new ScalarEvidenceField(width, height, values, valid, lineage);
    }

    /** Maps semantic intensity first and then applies a scalar-domain separable filter. */
    public static ScalarEvidenceField mapThenConvolve(int width, int height, int[] argb, boolean[] valid,
        IntToDoubleFunction mapping, EvidenceFieldLineage lineage, double[] kernel) {
        return mapArgb(width, height, argb, valid, mapping, lineage).convolveSeparable(kernel);
    }
}
