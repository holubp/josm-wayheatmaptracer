package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded worker-local counters for one modern alignment attempt. */
public final class ModernDiagnosticCounters {
    private static final int MAX_KEYS = 128;
    private static final ThreadLocal<Map<String, Number>> CURRENT = new ThreadLocal<>();

    private ModernDiagnosticCounters() { }

    /** Starts one worker attempt. */
    public static void begin() {
        CURRENT.set(new LinkedHashMap<>());
    }

    /** Records one finite count or duration if an attempt is active. */
    public static void record(String name, long value) {
        put(name, value);
    }

    /** Records one finite physical statistic if an attempt is active. */
    public static void record(String name, double value) {
        if (Double.isFinite(value)) put(name, value);
    }

    /** Accumulates repeated final-route timings. */
    public static void add(String name, long value) {
        Map<String, Number> counters = CURRENT.get();
        if (counters == null) return;
        Number previous = counters.get(name);
        put(name, previous == null ? value : Math.addExact(previous.longValue(), value));
    }

    /** Returns a detached immutable counter snapshot. */
    public static Map<String, Number> snapshot() {
        Map<String, Number> counters = CURRENT.get();
        return counters == null ? Map.of() : Map.copyOf(counters);
    }

    /** Releases worker-local state after completion or failure. */
    public static void end() {
        CURRENT.remove();
    }

    private static void put(String name, Number value) {
        Map<String, Number> counters = CURRENT.get();
        if (counters == null) return;
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9.]{0,80}")
                || !counters.containsKey(name) && counters.size() >= MAX_KEYS) {
            throw new IllegalArgumentException("Modern diagnostic counter inventory is invalid");
        }
        counters.put(name, value);
    }
}
