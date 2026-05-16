package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Parses the Strava CloudFront cookie header copied from browser/JOSM tooling into individual settings fields.
 */
public final class StravaCookieParser {
    private static final String KEY_PAIR_ID = "CloudFront-Key-Pair-Id";
    private static final String POLICY = "CloudFront-Policy";
    private static final String SIGNATURE = "CloudFront-Signature";
    private static final String SESSION = "_strava_idcf";

    private StravaCookieParser() {
    }

    /**
     * Splits a semicolon-separated cookie header into the required Strava access values.
     *
     * @param cookieHeader raw cookie header pasted by the user
     * @return parsed access values
     * @throws NullPointerException if {@code cookieHeader} is {@code null}
     * @throws IllegalArgumentException if any required cookie is missing
     */
    public static StravaCookieValues parse(String cookieHeader) {
        Objects.requireNonNull(cookieHeader, "cookieHeader");
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : cookieHeader.split(";")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                values.put(name, value);
            }
        }

        return new StravaCookieValues(
            required(values, KEY_PAIR_ID),
            required(values, POLICY),
            required(values, SIGNATURE),
            required(values, SESSION)
        );
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cookie header is missing " + name + ".");
        }
        return value;
    }
}
