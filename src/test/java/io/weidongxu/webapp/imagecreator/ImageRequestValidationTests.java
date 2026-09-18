package io.weidongxu.webapp.imagecreator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageRequestValidationTests {

    @Test
    void rejectsHighConfidenceInputImageShortage() {
        ImageRequestValidation result =
                modelResult("multi_image_edit", 2, "high");

        ImageRequestValidation.enforcePolicy(result, "2448x3264", 1);

        assertThat(result.input_images_match).isFalse();
        assertThat(result.provided_input_images).isEqualTo(1);
        assertThat(result.minimum_input_images).isEqualTo(2);
    }

    @Test
    void computesOrientationMismatchOutsideTheModel() {
        ImageRequestClassification classification = new ImageRequestClassification();
        classification.intended_orientation = "landscape";
        classification.orientation_confidence = "high";
        classification.orientation_reason = "The prompt explicitly specifies 4:3 horizontal.";
        classification.input_image_intent = "single_image_edit";
        classification.minimum_input_images = 1;
        classification.input_image_confidence = "high";
        classification.input_image_reason = "The prompt requests an image edit.";

        ImageRequestValidation result =
                ImageRequestValidation.fromClassification(classification, "2448x3264", 1);

        assertThat(result.intended_orientation).isEqualTo("landscape");
        assertThat(result.selected_orientation).isEqualTo("portrait");
        assertThat(result.orientation_matches).isFalse();
        assertThat(result.provided_input_images).isEqualTo(1);
        assertThat(result.input_images_match).isTrue();
    }

    @Test
    void allowsGenerationWithoutInputImages() {
        ImageRequestValidation result =
                modelResult("generation", 0, "high");

        ImageRequestValidation.enforcePolicy(result, "2448x3264", 0);

        assertThat(result.input_images_match).isTrue();
    }

    @Test
    void allowsLowerConfidenceShortage() {
        ImageRequestValidation result =
                modelResult("single_image_edit", 1, "medium");

        ImageRequestValidation.enforcePolicy(result, "2448x3264", 0);

        assertThat(result.input_images_match).isTrue();
    }

    @Test
    void rejectsInconsistentModelOutput() {
        assertThatThrownBy(() -> ImageRequestValidation.enforcePolicy(
                modelResult("generation", 1, "high"), "2448x3264", 0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ImageRequestValidation.enforcePolicy(
                modelResult("unknown", 0, "high"), "2448x3264", 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsRequestWhenValidationIsUnavailable() {
        ImageRequestValidation result =
                ImageRequestValidation.allowWhenUnavailable("2448x3264", 1);

        assertThat(result.orientation_matches).isTrue();
        assertThat(result.input_images_match).isTrue();
        assertThat(result.provided_input_images).isEqualTo(1);
        assertThat(result.input_image_confidence).isEqualTo("low");
    }

    private ImageRequestValidation modelResult(String intent, int minimum, String confidence) {
        ImageRequestValidation result = new ImageRequestValidation();
        result.intended_orientation = "unspecified";
        result.selected_orientation = "ignored";
        result.orientation_matches = true;
        result.orientation_confidence = "high";
        result.orientation_reason = "No orientation was specified.";
        result.input_image_intent = intent;
        result.minimum_input_images = minimum;
        result.provided_input_images = 99;
        result.input_images_match = true;
        result.input_image_confidence = confidence;
        result.input_image_reason = "Model reason";
        return result;
    }
}
