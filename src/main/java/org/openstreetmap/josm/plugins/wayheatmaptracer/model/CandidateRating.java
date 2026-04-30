package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

public record CandidateRating(String rating, List<String> negativeFeatures) {
    public CandidateRating {
        rating = rating == null ? "" : rating;
        negativeFeatures = negativeFeatures == null ? List.of() : List.copyOf(negativeFeatures);
    }

    public boolean isEmpty() {
        return rating.isBlank() && negativeFeatures.isEmpty();
    }

    public String toJson() {
        return "{"
            + "\"rating\":\"" + escape(rating) + "\","
            + "\"negativeFeatures\":" + stringArray(negativeFeatures)
            + "}";
    }

    private static String stringArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(escape(values.get(i))).append('"');
        }
        return builder.append(']').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
