package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;

/**
 * Per-solve, bounded, first-use cache for exact decision-region admission checks.
 *
 * <p>The caller remains responsible for performing the check at its original traversal site.
 * Evicted entries are deliberately recomputed rather than changing the inference state space.</p>
 */
final class TransitionAdmissionMemo {
    private final int capacity;
    private final Map<Key, Boolean> values;

    TransitionAdmissionMemo(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Admission memo capacity must be positive");
        }
        this.capacity = capacity;
        this.values = new LinkedHashMap<>(capacity + 1, 0.75f, true);
    }

    boolean allowed(int startProfile, int startState, int endState,
        List<InferenceProfile> profiles, MetricRegion decisionRegion) {
        if (decisionRegion == null) {
            return true;
        }
        Key key = new Key(startProfile, startState, endState, 0);
        Boolean cached = values.get(key);
        if (cached != null) {
            return cached;
        }
        MetricPoint start = profiles.get(startProfile).point(startState);
        MetricPoint end = profiles.get(startProfile + 1).point(endState);
        boolean value = decisionRegion.contains(start) && decisionRegion.contains(end)
            && decisionRegion.containsSegment(start, end);
        remember(key, value);
        return value;
    }

    boolean allowed(int startProfile, int startState, int endState, int discriminator,
        BooleanSupplier predicate) {
        Key key = new Key(startProfile, startState, endState, discriminator);
        Boolean cached = values.get(key);
        if (cached != null) {
            return cached;
        }
        boolean value = predicate.getAsBoolean();
        remember(key, value);
        return value;
    }

    private void remember(Key key, boolean value) {
        values.put(key, value);
        if (values.size() > capacity) {
            values.remove(values.keySet().iterator().next());
        }
    }

    int size() {
        return values.size();
    }

    private record Key(int profile, int startState, int endState, int discriminator) { }
}
