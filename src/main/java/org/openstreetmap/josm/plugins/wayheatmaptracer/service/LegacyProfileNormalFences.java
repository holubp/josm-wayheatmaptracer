package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.openstreetmap.josm.data.coor.EastNorth;

/** Maps immutable original fixed-node occurrences to dense Legacy sampling-profile fences. */
public final class LegacyProfileNormalFences {
    private LegacyProfileNormalFences() {
    }

    /**
     * Plans dense-profile fences using the exact path-length and target-station arithmetic used by
     * {@link PolylineMath#resampleByCount(List, int)}. Off-grid fixed occurrences fence both
     * neighboring dense rows; they are never snapped to a node or used as a sampled position.
     *
     * @param originalPolyline original selected geometry
     * @param denseProfileCount number of uniformly resampled Legacy profiles
     * @param fixedOriginalIndices original occurrence indices, including both endpoints
     * @return immutable fences or an explicit immediate-policy fallback reason
     */
    public static Plan plan(
        List<EastNorth> originalPolyline,
        int denseProfileCount,
        List<Integer> fixedOriginalIndices
    ) {
        if (denseProfileCount < 2) {
            return fallback("invalid-profile-count");
        }
        if (originalPolyline == null || fixedOriginalIndices == null || originalPolyline.size() < 2
                || fixedOriginalIndices.size() < 2) {
            return fallback("invalid-authority");
        }
        if (fixedOriginalIndices.get(0) == null || fixedOriginalIndices.get(0) != 0
                || fixedOriginalIndices.get(fixedOriginalIndices.size() - 1) == null
                || fixedOriginalIndices.get(fixedOriginalIndices.size() - 1) != originalPolyline.size() - 1) {
            return fallback("invalid-authority");
        }

        double[] cumulative = new double[originalPolyline.size()];
        for (int i = 0; i < originalPolyline.size(); i++) {
            EastNorth point = originalPolyline.get(i);
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())) {
                return fallback("non-finite-geometry");
            }
            if (i > 0) {
                double segment = originalPolyline.get(i - 1).distance(point);
                if (!Double.isFinite(segment)) {
                    return fallback("non-finite-geometry");
                }
                cumulative[i] = cumulative[i - 1] + segment;
                if (!Double.isFinite(cumulative[i])) {
                    return fallback("non-finite-geometry");
                }
            }
        }
        double total = cumulative[cumulative.length - 1];
        if (total == 0.0) {
            return fallback("degenerate-geometry");
        }

        int previous = -1;
        for (Integer index : fixedOriginalIndices) {
            if (index == null || index <= previous || index < 0 || index >= originalPolyline.size()) {
                return fallback("invalid-authority");
            }
            previous = index;
        }

        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        boundaries.add(denseProfileCount - 1);
        for (int fixedIndex : fixedOriginalIndices) {
            double station = cumulative[fixedIndex];
            int low = 0;
            int high = denseProfileCount;
            while (low < high) {
                int middle = (low + high) >>> 1;
                double target = total * middle / (denseProfileCount - 1.0);
                if (target < station) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            if (low < denseProfileCount
                    && total * low / (denseProfileCount - 1.0) == station) {
                boundaries.add(low);
            } else if (low > 0 && low < denseProfileCount) {
                boundaries.add(low - 1);
                boundaries.add(low);
            }
        }
        return new Plan(true, "eligible", boundaries);
    }

    private static Plan fallback(String reason) {
        return new Plan(false, reason, Set.of());
    }

    /** Immutable result of original-occurrence fence mapping. */
    public record Plan(boolean eligible, String reason, Set<Integer> profileBoundaries) {
        public Plan {
            profileBoundaries = Set.copyOf(profileBoundaries);
        }
    }
}
