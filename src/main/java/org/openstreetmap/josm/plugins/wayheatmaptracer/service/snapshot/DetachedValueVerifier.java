package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** Recursively proves that worker input contains no live UI, OSM, credential, or opaque state. */
public final class DetachedValueVerifier {
    private DetachedValueVerifier() {
    }

    /** Validates an object graph intended for background computation or deterministic replay. */
    public static void verify(Object root) {
        inspect(root, new IdentityHashMap<>());
    }

    /** Validates a type without requiring the caller to construct prohibited live state. */
    public static void verifyType(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("Detached snapshot type is required");
        }
        rejectForbidden(type);
        if (!scalar(type) && !type.isRecord() && !type.isArray()
            && !Iterable.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)
            && type != Optional.class && type != OptionalDouble.class) {
            throw new IllegalArgumentException("Detached snapshot contains an opaque unproved type");
        }
    }

    private static void inspect(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null || scalar(value.getClass()) || visited.put(value, Boolean.TRUE) != null) {
            return;
        }
        Class<?> type = value.getClass();
        rejectForbidden(type);
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(item -> inspect(item, visited));
            return;
        }
        if (value instanceof OptionalDouble) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                inspect(key, visited);
                inspect(item, visited);
            });
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> inspect(item, visited));
            return;
        }
        if (type.isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                inspect(Array.get(value, index), visited);
            }
            return;
        }
        if (!type.getName().startsWith(
            "org.openstreetmap.josm.plugins.wayheatmaptracer.")) {
            throw new IllegalArgumentException("Detached snapshot contains an opaque unproved type");
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                inspect(field.get(value), visited);
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalArgumentException("Cannot prove detached snapshot object graph", error);
            }
        }
    }

    private static void rejectForbidden(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("org.openstreetmap.josm.data.") || name.contains("MapView")
            || name.contains("Projection") || name.contains("Preferences") || name.contains("Credential")
            || name.equals("java.awt.image.BufferedImage") || type.isSynthetic()) {
            throw new IllegalArgumentException("Detached snapshot contains forbidden live state type");
        }
    }

    private static boolean scalar(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || Number.class.isAssignableFrom(type)
            || type == String.class || type == Boolean.class || type == Character.class;
    }
}
