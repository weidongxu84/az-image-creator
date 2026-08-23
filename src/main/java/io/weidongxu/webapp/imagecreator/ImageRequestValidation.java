package io.weidongxu.webapp.imagecreator;

import java.util.Locale;
import java.util.Set;

public class ImageRequestValidation {

    private static final Set<String> INTENTS =
            Set.of("generation", "single_image_edit", "multi_image_edit", "ambiguous");
    private static final Set<String> CONFIDENCE_LEVELS =
            Set.of("high", "medium", "low");

    public String intended_orientation;
    public String selected_orientation;
    public boolean orientation_matches;
    public String orientation_confidence;
    public String orientation_reason;
    public String input_image_intent;
    public int minimum_input_images;
    public int provided_input_images;
    public boolean input_images_match;
    public String input_image_confidence;
    public String input_image_reason;

    static ImageRequestValidation enforcePolicy(
            ImageRequestValidation modelResult, String size, int providedInputImages) {
        if (modelResult == null) {
            throw new IllegalStateException("Image request validation returned no result");
        }
        if (providedInputImages < 0) {
            throw new IllegalArgumentException("Provided input image count cannot be negative");
        }

        ImageOrientationValidation orientation = new ImageOrientationValidation();
        orientation.intended_orientation = modelResult.intended_orientation;
        orientation.selected_orientation = modelResult.selected_orientation;
        orientation.matches = modelResult.orientation_matches;
        orientation.confidence = modelResult.orientation_confidence;
        orientation.reason = modelResult.orientation_reason;
        ImageOrientationValidation.enforcePolicy(orientation, size);

        String intent = normalize(modelResult.input_image_intent);
        String confidence = normalize(modelResult.input_image_confidence);
        int minimum = modelResult.minimum_input_images;
        if (!INTENTS.contains(intent)) {
            throw new IllegalStateException("Image request validation returned an invalid input-image intent");
        }
        if (!CONFIDENCE_LEVELS.contains(confidence)) {
            throw new IllegalStateException("Image request validation returned an invalid input-image confidence");
        }
        if (minimum < 0 || minimum > 100) {
            throw new IllegalStateException("Image request validation returned an invalid minimum input-image count");
        }
        if (("generation".equals(intent) || "ambiguous".equals(intent)) && minimum != 0) {
            throw new IllegalStateException("Image request validation returned an inconsistent generation intent");
        }
        if ("single_image_edit".equals(intent) && minimum < 1) {
            throw new IllegalStateException("Image request validation returned an inconsistent single-image edit intent");
        }
        if ("multi_image_edit".equals(intent) && minimum < 2) {
            throw new IllegalStateException("Image request validation returned an inconsistent multi-image edit intent");
        }

        boolean highConfidenceShortage =
                "high".equals(confidence) && providedInputImages < minimum;

        modelResult.intended_orientation = orientation.intended_orientation;
        modelResult.selected_orientation = orientation.selected_orientation;
        modelResult.orientation_matches = orientation.matches;
        modelResult.orientation_confidence = orientation.confidence;
        modelResult.orientation_reason = orientation.reason;
        modelResult.input_image_intent = intent;
        modelResult.provided_input_images = providedInputImages;
        modelResult.input_images_match = !highConfidenceShortage;
        modelResult.input_image_confidence = confidence;
        if (modelResult.input_image_reason == null || modelResult.input_image_reason.isBlank()) {
            modelResult.input_image_reason = highConfidenceShortage
                    ? "The prompt requires more input images than were provided."
                    : "No high-confidence input-image shortage was found.";
        }
        return modelResult;
    }

    static ImageRequestValidation allowWhenUnavailable(String size, int providedInputImages) {
        ImageRequestValidation result = new ImageRequestValidation();
        result.intended_orientation = "unspecified";
        result.selected_orientation = ImageOrientationValidation.selectedOrientation(size);
        result.orientation_matches = true;
        result.orientation_confidence = "low";
        result.orientation_reason = "Request validation was unavailable, so the request was allowed.";
        result.input_image_intent = "ambiguous";
        result.minimum_input_images = 0;
        result.provided_input_images = providedInputImages;
        result.input_images_match = true;
        result.input_image_confidence = "low";
        result.input_image_reason = "Request validation was unavailable, so the request was allowed.";
        return result;
    }

    ImageOrientationValidation orientationValidation() {
        ImageOrientationValidation result = new ImageOrientationValidation();
        result.intended_orientation = intended_orientation;
        result.selected_orientation = selected_orientation;
        result.matches = orientation_matches;
        result.confidence = orientation_confidence;
        result.reason = orientation_reason;
        return result;
    }

    InputImageValidation inputImageValidation() {
        InputImageValidation result = new InputImageValidation();
        result.intent = input_image_intent;
        result.minimum_input_images = minimum_input_images;
        result.provided_input_images = provided_input_images;
        result.matches = input_images_match;
        result.confidence = input_image_confidence;
        result.reason = input_image_reason;
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
