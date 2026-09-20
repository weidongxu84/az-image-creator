package io.weidongxu.webapp.imagecreator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageOrientationValidationTests {

    @Test
    void rejectsHighConfidenceMismatch() {
        ImageOrientationValidation result = modelResult("portrait", "high");

        ImageOrientationValidation.enforcePolicy(result, "3264x2448");

        assertThat(result.matches).isFalse();
        assertThat(result.selected_orientation).isEqualTo("landscape");
    }

    @Test
    void allowsUnspecifiedAndLowerConfidenceResults() {
        ImageOrientationValidation unspecified = modelResult("unspecified", "high");
        ImageOrientationValidation uncertain = modelResult("portrait", "medium");

        ImageOrientationValidation.enforcePolicy(unspecified, "3264x2448");
        ImageOrientationValidation.enforcePolicy(uncertain, "3264x2448");

        assertThat(unspecified.matches).isTrue();
        assertThat(uncertain.matches).isTrue();
    }

    @Test
    void classifiesSelectedSizeOrientation() {
        assertThat(ImageOrientationValidation.selectedOrientation("3264x2448")).isEqualTo("landscape");
        assertThat(ImageOrientationValidation.selectedOrientation("2448x3264")).isEqualTo("portrait");
        assertThat(ImageOrientationValidation.selectedOrientation("2880x2880")).isEqualTo("square");
    }

    @Test
    void resolvesHighConfidenceAutoSizeForPreviewAndFullGeneration() {
        ImageOrientationValidation portrait = modelResult("portrait", "high");
        ImageOrientationValidation landscape = modelResult("landscape", "high");
        ImageOrientationValidation square = modelResult("square", "high");

        ImageOrientationValidation.enforcePolicy(portrait, "auto", true);
        ImageOrientationValidation.enforcePolicy(landscape, "auto", false);
        ImageOrientationValidation.enforcePolicy(square, "auto", true);

        assertThat(portrait.resolved_size).isEqualTo("1088x1440");
        assertThat(portrait.size_selection_required).isFalse();
        assertThat(landscape.resolved_size).isEqualTo("3264x2448");
        assertThat(landscape.size_selection_required).isFalse();
        assertThat(square.resolved_size).isEqualTo("1440x1440");
        assertThat(square.size_selection_required).isFalse();
    }

    @Test
    void requiresExplicitSizeWhenAutoOrientationIsNotHighConfidence() {
        ImageOrientationValidation uncertain = modelResult("portrait", "medium");
        ImageOrientationValidation unspecified = modelResult("unspecified", "high");

        ImageOrientationValidation.enforcePolicy(uncertain, "auto", true);
        ImageOrientationValidation.enforcePolicy(unspecified, "auto", true);

        assertThat(uncertain.size_selection_required).isTrue();
        assertThat(unspecified.size_selection_required).isTrue();
        assertThat(uncertain.resolved_size).isEqualTo("auto");
    }

    @Test
    void rejectsInvalidModelOutputAndSize() {
        assertThatThrownBy(() ->
                ImageOrientationValidation.enforcePolicy(modelResult("diagonal", "high"), "1024x1024"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ImageOrientationValidation.selectedOrientation("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsRequestWhenValidationIsUnavailable() {
        ImageOrientationValidation result =
                ImageOrientationValidation.allowWhenUnavailable("2448x3264");

        assertThat(result.matches).isTrue();
        assertThat(result.intended_orientation).isEqualTo("unspecified");
        assertThat(result.selected_orientation).isEqualTo("portrait");
        assertThat(result.confidence).isEqualTo("low");
    }

    private ImageOrientationValidation modelResult(String intended, String confidence) {
        ImageOrientationValidation result = new ImageOrientationValidation();
        result.intended_orientation = intended;
        result.selected_orientation = "ignored";
        result.matches = true;
        result.confidence = confidence;
        result.reason = "Model reason";
        return result;
    }
}
