package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Optional human preview rating and negative-feature tags for detector calibration.
 *
 * @param rating one of {@code ++}, {@code +}, {@code 0}, {@code -}, {@code --}, or blank
 * @param negativeFeatures user-selected failure tags such as off-line or unnecessary kinks
 */
public record CandidateRating(String rating, List<String> negativeFeatures) {
    public CandidateRating {
        rating = rating == null ? "" : rating;
        negativeFeatures = negativeFeatures == null ? List.of() : List.copyOf(negativeFeatures);
    }

    /**
     * Checks whether the user left the candidate unrated and untagged.
     *
     * @return {@code true} when there is no calibration feedback
     */
    public boolean isEmpty() {
        return rating.isBlank() && negativeFeatures.isEmpty();
    }

    /**
     * Serializes the rating for debug bundles.
     *
     * @return JSON object string with rating and negative feature tags
     */
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
