package io.weidongxu.webapp.imagecreator;

import java.util.Locale;
import java.util.Set;

public class ImageOrientationValidation {

    private static final Set<String> ORIENTATIONS =
            Set.of("landscape", "portrait", "square", "unspecified");
    private static final Set<String> CONFIDENCE_LEVELS =
            Set.of("high", "medium", "low");

    public String intended_orientation;
    public String selected_orientation;
    public boolean matches;
    public String confidence;
    public String reason;

    static ImageOrientationValidation enforcePolicy(ImageOrientationValidation modelResult, String size) {
        if (modelResult == null) {
            throw new IllegalStateException("Orientation validation returned no result");
        }

        String intended = normalize(modelResult.intended_orientation);
        String confidence = normalize(modelResult.confidence);
        if (!ORIENTATIONS.contains(intended)) {
            throw new IllegalStateException("Orientation validation returned an invalid intended orientation");
        }
        if (!CONFIDENCE_LEVELS.contains(confidence)) {
            throw new IllegalStateException("Orientation validation returned an invalid confidence");
        }

        String selected = selectedOrientation(size);
        boolean highConfidenceMismatch = "high".equals(confidence)
                && !"unspecified".equals(intended)
                && !selected.equals(intended);

        modelResult.intended_orientation = intended;
        modelResult.selected_orientation = selected;
        modelResult.confidence = confidence;
        modelResult.matches = !highConfidenceMismatch;
        if (modelResult.reason == null || modelResult.reason.isBlank()) {
            modelResult.reason = highConfidenceMismatch
                    ? "The prompt's intended orientation does not match the selected image size."
                    : "No high-confidence orientation mismatch was found.";
        }
        return modelResult;
    }

    static ImageOrientationValidation allowWhenUnavailable(String size) {
        ImageOrientationValidation result = new ImageOrientationValidation();
        result.intended_orientation = "unspecified";
        result.selected_orientation = selectedOrientation(size);
        result.matches = true;
        result.confidence = "low";
        result.reason = "Orientation validation was unavailable, so the request was allowed.";
        return result;
    }

    static String selectedOrientation(String size) {
        if (size == null || !size.matches("\\d+x\\d+")) {
            throw new IllegalArgumentException("Invalid image size: " + size);
        }
        String[] parts = size.split("x", 2);
        int width = Integer.parseInt(parts[0]);
        int height = Integer.parseInt(parts[1]);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image size: " + size);
        }
        if (width == height) {
            return "square";
        }
        return width > height ? "landscape" : "portrait";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
