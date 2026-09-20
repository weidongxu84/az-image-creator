package io.weidongxu.webapp.imagecreator;

import java.util.Locale;
import java.util.Set;

public class ImageOrientationValidation {

    private static final String AUTO_SIZE = "auto";
    private static final String LANDSCAPE_SIZE = "3264x2448";
    private static final String LANDSCAPE_PREVIEW_SIZE = "1440x1088";
    private static final String PORTRAIT_SIZE = "2448x3264";
    private static final String PORTRAIT_PREVIEW_SIZE = "1088x1440";
    private static final String SQUARE_SIZE = "2880x2880";
    private static final String SQUARE_PREVIEW_SIZE = "1440x1440";
    private static final Set<String> ORIENTATIONS =
            Set.of("landscape", "portrait", "square", "unspecified");
    private static final Set<String> CONFIDENCE_LEVELS =
            Set.of("high", "medium", "low");

    public String intended_orientation;
    public String selected_orientation;
    public boolean matches;
    public String confidence;
    public String reason;
    public String resolved_size;
    public boolean size_selection_required;

    static ImageOrientationValidation enforcePolicy(
            ImageOrientationValidation modelResult, String size, boolean preview) {
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

        boolean auto = AUTO_SIZE.equals(normalize(size));
        boolean canResolveAuto = auto
                && "high".equals(confidence)
                && !"unspecified".equals(intended);
        String resolvedSize = canResolveAuto ? autoSize(intended, preview) : size;
        String selected = auto
                ? (canResolveAuto ? intended : AUTO_SIZE)
                : selectedOrientation(size);
        boolean highConfidenceMismatch = "high".equals(confidence)
                && !"unspecified".equals(intended)
                && !auto
                && !selected.equals(intended);

        modelResult.intended_orientation = intended;
        modelResult.selected_orientation = selected;
        modelResult.confidence = confidence;
        modelResult.matches = !highConfidenceMismatch;
        modelResult.resolved_size = resolvedSize;
        modelResult.size_selection_required = auto && !canResolveAuto;
        if (modelResult.size_selection_required) {
            modelResult.reason = "The prompt orientation could not be determined with high confidence. Please choose a size.";
        } else if (modelResult.reason == null || modelResult.reason.isBlank()) {
            modelResult.reason = highConfidenceMismatch
                    ? "The prompt's intended orientation does not match the selected image size."
                    : "No high-confidence orientation mismatch was found.";
        }
        return modelResult;
    }

    static ImageOrientationValidation enforcePolicy(ImageOrientationValidation modelResult, String size) {
        return enforcePolicy(modelResult, size, false);
    }

    static ImageOrientationValidation allowWhenUnavailable(String size, boolean preview) {
        ImageOrientationValidation result = new ImageOrientationValidation();
        result.intended_orientation = "unspecified";
        boolean auto = AUTO_SIZE.equals(normalize(size));
        result.selected_orientation = auto ? AUTO_SIZE : selectedOrientation(size);
        result.matches = true;
        result.confidence = "low";
        result.resolved_size = size;
        result.size_selection_required = auto;
        result.reason = auto
                ? "The prompt orientation could not be determined. Please choose a size."
                : "Orientation validation was unavailable, so the request was allowed.";
        return result;
    }

    static ImageOrientationValidation allowWhenUnavailable(String size) {
        return allowWhenUnavailable(size, false);
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

    private static String autoSize(String orientation, boolean preview) {
        if ("landscape".equals(orientation)) {
            return preview ? LANDSCAPE_PREVIEW_SIZE : LANDSCAPE_SIZE;
        }
        if ("portrait".equals(orientation)) {
            return preview ? PORTRAIT_PREVIEW_SIZE : PORTRAIT_SIZE;
        }
        return preview ? SQUARE_PREVIEW_SIZE : SQUARE_SIZE;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
