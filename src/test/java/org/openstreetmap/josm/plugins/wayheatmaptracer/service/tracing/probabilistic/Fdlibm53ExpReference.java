/*
 * Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
 * Permission to use, copy, modify, and distribute this software is freely
 * granted, provided that this notice is preserved.
 */
package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/**
 * Independent test-only transliteration of fdlibm e_exp.c, revision 1.6.
 * Source: https://www.netlib.org/fdlibm/e_exp.c (retrieved 2026-09-29).
 * Uses binary64 arithmetic and word manipulation, never Math/StrictMath exp.
 * This is the published reference algorithm, not the production solver.
 */
final class Fdlibm53ExpReference {
    private Fdlibm53ExpReference() { }

    static double exp(double x) {
        double hi = 0.0;
        double lo = 0.0;
        int k = 0;
        int high = (int) (Double.doubleToRawLongBits(x) >>> 32);
        int sign = (high >>> 31) & 1;
        high &= 0x7fffffff;
        if (high >= 0x40862e42) {
            if (high >= 0x7ff00000) {
                if (((high & 0xfffff) | (int) Double.doubleToRawLongBits(x)) != 0) return x + x;
                return sign == 0 ? x : 0.0;
            }
            if (x > 7.09782712893383973096e2) return Double.POSITIVE_INFINITY;
            if (x < -7.45133219101941108420e2) return 0.0;
        }
        double ln2High = 6.93147180369123816490e-1;
        double ln2Low = 1.90821492927058770002e-10;
        if (high > 0x3fd62e42) {
            if (high < 0x3ff0a2b2) {
                hi = x - (sign == 0 ? ln2High : -ln2High);
                lo = sign == 0 ? ln2Low : -ln2Low;
                k = 1 - sign - sign;
            } else {
                k = (int) (1.44269504088896338700 * x + (sign == 0 ? 0.5 : -0.5));
                hi = x - k * ln2High;
                lo = k * ln2Low;
            }
            x = hi - lo;
        } else if (high < 0x3e300000) {
            return 1.0 + x;
        }
        double t = x * x;
        double c = x - t * (1.66666666666666019037e-1
            + t * (-2.77777777770155933842e-3
            + t * (6.61375632143793436117e-5
            + t * (-1.65339022054652515390e-6 + t * 4.13813679705723846039e-8))));
        if (k == 0) return 1.0 - ((x * c) / (c - 2.0) - x);
        double y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
        long bits = Double.doubleToRawLongBits(y);
        if (k >= -1021) return Double.longBitsToDouble(bits + ((long) k << 52));
        return Double.longBitsToDouble(bits + ((long) (k + 1000) << 52))
            * 9.33263618503218878990e-302;
    }
}
